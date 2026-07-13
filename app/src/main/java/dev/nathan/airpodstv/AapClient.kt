package dev.nathan.airpodstv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.Executors

/**
 * Minimal AAP (Apple Accessory Protocol) client over a BR/EDR L2CAP socket (PSM 0x1001).
 * Gives us what BLE beacons can't: exact battery percentages, live ear detection,
 * ANC mode control, and the Identity Resolving Key via the key exchange.
 *
 * Packet formats from librepods' protocol docs + CAPod's DefaultAapDeviceProfile.
 */
@SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
class AapClient(
    private val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onSession(active: Boolean)
        fun onBattery(batteries: Map<Component, Battery>)
        fun onEar(primaryInEar: Boolean, secondaryInEar: Boolean, anyInCase: Boolean)
        fun onAncMode(wireMode: Int)
        fun onIrk(irk: ByteArray)
    }

    enum class Component { LEFT, RIGHT, CASE }
    data class Battery(val percent: Int, val charging: Boolean)

    companion object {
        private const val TAG = "AapClient"
        private const val PSM = 0x1001

        const val ANC_OFF = 0x01
        const val ANC_ON = 0x02
        const val ANC_TRANSPARENCY = 0x03
        const val ANC_ADAPTIVE = 0x04

        private val HANDSHAKE = byteArrayOf(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        private val INIT_EXT = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x4d, 0x00, 0xd7.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        private val NOTIFICATIONS_A = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x0f, 0x00,
            0xff.toByte(), 0xff.toByte(), 0xef.toByte(), 0xff.toByte()
        )
        private val NOTIFICATIONS_B = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x0f, 0x00,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()
        )
        private val KEY_REQUEST = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x05, 0x00
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AapWriter")
    }
    private val framer = AapPacketFramer()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var running = false

    val isActive: Boolean get() = running && socket != null

    fun start() {
        if (running) return
        running = true
        Thread({ runSession() }, "AapClient").start()
    }

    fun stop() {
        running = false
        writer.shutdownNow()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun setAncMode(wireMode: Int) {
        send(
            byteArrayOf(
                0x04, 0x00, 0x04, 0x00, 0x09, 0x00,
                0x0D, wireMode.toByte(), 0x00, 0x00, 0x00
            )
        )
    }

    private fun send(data: ByteArray) {
        if (writer.isShutdown) return
        writer.execute {
            try {
                socket?.outputStream?.let {
                    it.write(data)
                    it.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed: $e")
            }
        }
    }

    private fun createSocket(): BluetoothSocket {
        // Hidden BR/EDR L2CAP constructor, using the same approach as CAPod's L2capSocketFactory.
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
        val method = BluetoothDevice::class.java.getDeclaredMethod(
            "createInsecureL2capSocket", Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(device, PSM) as BluetoothSocket
    }

    private fun runSession() {
        var sock: BluetoothSocket? = null
        try {
            sock = createSocket()
            socket = sock
            sock.connect()
            Log.i(TAG, "L2CAP connected to ${device.address}")

            val out = sock.outputStream
            out.write(HANDSHAKE); out.flush()
            Thread.sleep(300)
            out.write(INIT_EXT); out.flush()
            out.write(NOTIFICATIONS_A); out.flush()
            out.write(NOTIFICATIONS_B); out.flush()
            Thread.sleep(300)
            out.write(KEY_REQUEST); out.flush()

            main.post { listener.onSession(true) }

            val buf = ByteArray(4096)
            while (running) {
                val n = sock.inputStream.read(buf)
                if (n <= 0) break
                for (packet in framer.feed(buf.copyOf(n))) {
                    try {
                        parseMessage(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "parse error: $e")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AAP session failed: $e")
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            socket = null
            running = false
            main.post { listener.onSession(false) }
        }
    }

    private fun parseMessage(msg: ByteArray) {
        // AAP frame: 04 00 04 00 [cmd lo] [cmd hi] [payload...]
        if (msg.size < 6) return
        if (msg[0] != 0x04.toByte() || msg[1] != 0x00.toByte() ||
            msg[2] != 0x04.toByte() || msg[3] != 0x00.toByte()
        ) return
        val cmd = (msg[4].toInt() and 0xFF) or ((msg[5].toInt() and 0xFF) shl 8)
        val payload = msg.copyOfRange(6, msg.size)

        when (cmd) {
            0x04 -> parseBattery(payload)
            0x06 -> parseEarDetection(payload)
            0x09 -> parseControl(payload)
            0x31 -> parseKeys(payload)
        }
    }

    private fun parseBattery(payload: ByteArray) {
        if (payload.isEmpty()) return
        val count = payload[0].toInt() and 0xFF
        val result = mutableMapOf<Component, Battery>()
        var offset = 1
        for (i in 0 until count) {
            if (offset + 5 > payload.size) break
            val type = payload[offset].toInt() and 0xFF
            val percent = payload[offset + 2].toInt() and 0xFF
            val status = payload[offset + 3].toInt() and 0xFF
            offset += 5
            // >100: 127 = phantom reading after case close, 255 = disconnected.
            if (percent > 100) continue
            val component = when (type) {
                0x04 -> Component.LEFT
                0x02 -> Component.RIGHT
                0x08 -> Component.CASE
                else -> continue
            }
            result[component] = Battery(percent, charging = status == 0x01)
        }
        if (result.isNotEmpty()) {
            main.post { listener.onBattery(result) }
        }
    }

    private fun parseEarDetection(payload: ByteArray) {
        if (payload.size < 2) return
        // 0x00 = in ear, 0x01 = out of ear, 0x02 = in case
        val primary = payload[0].toInt() and 0xFF
        val secondary = payload[1].toInt() and 0xFF
        main.post {
            listener.onEar(
                primaryInEar = primary == 0x00,
                secondaryInEar = secondary == 0x00,
                anyInCase = primary == 0x02 || secondary == 0x02,
            )
        }
    }

    private fun parseControl(payload: ByteArray) {
        if (payload.size < 2) return
        val settingId = payload[0].toInt() and 0xFF
        val value = payload[1].toInt() and 0xFF
        if (settingId == 0x0D && value in ANC_OFF..ANC_ADAPTIVE) {
            main.post { listener.onAncMode(value) }
        }
    }

    private fun parseKeys(payload: ByteArray) {
        // [count] then per key: type(1) unknown(1) length(1) unknown(1) data(length)
        if (payload.isEmpty()) return
        val keyCount = payload[0].toInt() and 0xFF
        var offset = 1
        for (i in 0 until keyCount) {
            if (offset + 4 > payload.size) break
            val keyType = payload[offset].toInt() and 0xFF
            val keyLength = payload[offset + 2].toInt() and 0xFF
            offset += 4
            if (offset + keyLength > payload.size) break
            val keyData = payload.copyOfRange(offset, offset + keyLength)
            offset += keyLength
            if (keyType == 0x01 && keyLength == 16) {
                Log.i(TAG, "IRK received via key exchange")
                main.post { listener.onIrk(keyData) }
            }
        }
    }
}
