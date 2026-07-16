package dev.nathan.airpodstv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
        fun onSession(state: SessionState, detail: String? = null)
        fun onBattery(batteries: Map<Component, Battery>)
        fun onEar(primary: Placement, secondary: Placement)
        fun onAncMode(wireMode: Int)
        fun onIrk(irk: ByteArray)
    }

    enum class SessionState { CONNECTING, ACTIVE, STOPPED, FAILED, UNSUPPORTED }
    enum class Placement { IN_EAR, OUT_OF_EAR, IN_CASE, UNKNOWN }
    enum class Component { LEFT, RIGHT, CASE }
    data class Battery(val percent: Int, val charging: Boolean)

    companion object {
        private const val TAG = "AapClient"
        private const val PSM = 0x1001
        private const val BETWEEN_ATTEMPT_SETTLE_MS = 1_000L
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L

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
    private val lifecycleLock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AapWriter")
    }

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var sessionReady = false

    private val timeoutLock = Any()

    @Volatile
    private var activeTimeout: SocketTimeout? = null

    private inner class SocketTimeout(
        val stage: String,
        private val expectedSocket: BluetoothSocket,
    ) : Runnable {
        @Volatile
        var fired = false
            private set

        override fun run() {
            val shouldClose = synchronized(timeoutLock) {
                if (activeTimeout !== this) {
                    false
                } else {
                    activeTimeout = null
                    if (running && !sessionReady && socket === expectedSocket) {
                        fired = true
                        true
                    } else {
                        false
                    }
                }
            }
            if (!shouldClose) return
            Log.w(TAG, "$stage timed out; closing its socket")
            try {
                expectedSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class TransportException(
        message: String,
        cause: Throwable?,
        val platformBlocked: Boolean,
    ) : IOException(message, cause)

    val isActive: Boolean get() = running && sessionReady

    fun start() {
        if (running) return
        stopRequested = false
        sessionReady = false
        running = true
        postSession(SessionState.CONNECTING, "quieting Bluetooth radio")
        Thread({ runSession() }, "AapClient").start()
    }

    fun stop() {
        val socketToClose = synchronized(lifecycleLock) {
            if (stopRequested) return
            stopRequested = true
            running = false
            sessionReady = false
            socket.also { socket = null }
        }
        cancelTimeout()
        writer.shutdownNow()
        try {
            socketToClose?.close()
        } catch (_: Exception) {
        }
        postSession(SessionState.STOPPED)
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
        if (!sessionReady || writer.isShutdown) return
        try {
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
        } catch (_: RejectedExecutionException) {
            Log.d(TAG, "send skipped because the AAP writer is stopping")
        }
    }

    private fun createSocket(security: AapTransportPlan.Security): BluetoothSocket {
        // These hidden methods create Classic BR/EDR L2CAP sockets. The similarly named
        // public L2CAP channel APIs on Android 11 are LE CoC and cannot carry AAP.
        if (!HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")) {
            throw SecurityException("Android refused hidden Bluetooth API access")
        }
        val method = BluetoothDevice::class.java.getDeclaredMethod(
            security.methodName, Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(device, PSM) as? BluetoothSocket
            ?: throw NoSuchMethodException("${security.methodName} returned no Bluetooth socket")
    }

    private fun connectL2cap(): BluetoothSocket {
        try {
            // Classic inquiry is owned by the system/Settings, not our BLE scanner. It is
            // still important to cancel because Android documents discovery as hostile to
            // outgoing BluetoothSocket connections.
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel Bluetooth discovery before AAP connect", e)
        }

        val failures = mutableListOf<String>()
        var lastFailure: Throwable? = null
        var timedOutAttempts = 0
        var unavailableAttempts = 0
        val strategies = AapTransportPlan.forSdk(Build.VERSION.SDK_INT)

        fun recordUnavailable(strategy: AapTransportPlan.Security, error: Throwable) {
            lastFailure = error
            if (AapTransportPlan.isHiddenApiAccessFailure(error)) unavailableAttempts++
            val detail = "${strategy.label} unavailable (${shortFailure(error)})"
            failures += detail
            Log.w(TAG, detail, error)
        }

        strategies.forEachIndexed { index, strategy ->
            if (!running || stopRequested) throw IOException("AAP session stopped")
            if (index > 0) {
                postSession(SessionState.CONNECTING, "resetting radio before ${strategy.label}")
                SystemClock.sleep(BETWEEN_ATTEMPT_SETTLE_MS)
                if (!running || stopRequested) throw IOException("AAP session stopped")
            }

            postSession(SessionState.CONNECTING, "trying ${strategy.label}")
            val attempt = try {
                createSocket(strategy)
            } catch (e: Exception) {
                recordUnavailable(strategy, e)
                return@forEachIndexed
            } catch (e: LinkageError) {
                recordUnavailable(strategy, e)
                return@forEachIndexed
            }

            val published = synchronized(lifecycleLock) {
                if (running && !stopRequested) {
                    socket = attempt
                    true
                } else {
                    false
                }
            }
            if (!published) {
                try {
                    attempt.close()
                } catch (_: Exception) {
                }
                throw IOException("AAP session stopped")
            }

            val startedAt = SystemClock.elapsedRealtime()
            val timeout = armTimeout(
                strategy.label,
                AapTransportPlan.timeoutMs(),
                attempt,
            )
            try {
                attempt.connect()
                if (!cancelTimeout(timeout)) {
                    throw IOException("${strategy.label} timed out")
                }
                if (!running || stopRequested) {
                    try {
                        attempt.close()
                    } catch (_: Exception) {
                    }
                    throw IOException("AAP session stopped")
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "${strategy.label} connected to ${device.address} in ${elapsed}ms")
                return attempt
            } catch (e: Exception) {
                val timedOut = timeout.fired
                cancelTimeout(timeout)
                synchronized(lifecycleLock) {
                    if (socket === attempt) socket = null
                }
                try {
                    attempt.close()
                } catch (_: Exception) {
                }
                if (!running || stopRequested) throw e

                lastFailure = e
                val detail = if (timedOut) {
                    timedOutAttempts++
                    "${strategy.label} timed out"
                } else {
                    "${strategy.label} failed (${shortFailure(e)})"
                }
                failures += detail
                Log.w(TAG, detail, e)
            }
        }

        throw TransportException(
            message = AapTransportPlan.failureDetail(failures),
            cause = lastFailure,
            // Missing hidden APIs are terminal on every Android version. Other failures
            // remain terminal only after both socket modes time out on Android 11.
            platformBlocked = unavailableAttempts == strategies.size ||
                (Build.VERSION.SDK_INT == 30 && timedOutAttempts == strategies.size),
        )
    }

    private fun shortFailure(error: Throwable): String {
        var cause = error
        while (cause is InvocationTargetException && cause.cause != null) cause = cause.cause!!
        return cause.message?.takeIf { it.isNotBlank() }?.take(80)
            ?: cause.javaClass.simpleName
    }

    private fun runSession() {
        var sock: BluetoothSocket? = null
        var failure: Throwable? = null
        var handshakeTimeout: SocketTimeout? = null
        try {
            sock = connectL2cap()
            if (!running) return

            val out = sock.outputStream
            val handshakeGuard = armTimeout("AAP handshake", HANDSHAKE_TIMEOUT_MS, sock)
            handshakeTimeout = handshakeGuard
            out.write(HANDSHAKE); out.flush()
            out.write(NOTIFICATIONS_A); out.flush()
            out.write(NOTIFICATIONS_B); out.flush()
            out.write(INIT_EXT); out.flush()
            out.write(KEY_REQUEST); out.flush()

            val buf = ByteArray(4096)
            val framer = AapPacketFramer()
            while (running) {
                val n = sock.inputStream.read(buf)
                if (n <= 0) break
                for (raw in framer.feed(buf.copyOf(n))) {
                    when (val packet = AapPacket.parse(raw)) {
                        is AapPacket.ConnectResponse -> {
                            if (packet.status != 0) {
                                throw IOException(
                                    "AirPods rejected AAP handshake (status 0x%04X)".format(packet.status)
                                )
                            }
                        }

                        is AapPacket.Message -> {
                            val update = AapMessageDecoder.usefulUpdate(packet)
                            if (update != null) {
                                markSessionActive(handshakeGuard)
                                dispatchUpdate(update)
                            }
                        }

                        is AapPacket.Disconnect -> throw IOException("AirPods ended the AAP session")
                        is AapPacket.Other -> Log.d(
                            TAG,
                            "Ignoring AAP packet type 0x%04X (${packet.raw.size} bytes)".format(packet.type)
                        )
                        null -> Log.w(
                            TAG,
                            "Ignoring malformed AAP packet (${raw.size} bytes)"
                        )
                    }
                }
            }
            if (running) failure = IOException("AirPods closed the AAP channel")
        } catch (e: Exception) {
            if (!stopRequested) {
                failure = if (handshakeTimeout?.fired == true) {
                    IOException("${handshakeTimeout.stage} timed out", e)
                } else {
                    e
                }
                Log.w(TAG, "AAP session failed", failure)
            }
        } finally {
            handshakeTimeout?.let { cancelTimeout(it) }
            cancelTimeout()
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            socket = null
            running = false
            sessionReady = false
            writer.shutdownNow()
            if (!stopRequested) {
                postSession(
                    if ((failure as? TransportException)?.platformBlocked == true) {
                        SessionState.UNSUPPORTED
                    } else {
                        SessionState.FAILED
                    },
                    describeFailure(failure),
                )
            }
        }
    }

    private fun markSessionActive(handshakeTimeout: SocketTimeout) {
        if (sessionReady) return
        if (!cancelTimeout(handshakeTimeout)) {
            throw IOException("${handshakeTimeout.stage} timed out")
        }
        sessionReady = true
        postSession(SessionState.ACTIVE)
    }

    private fun armTimeout(
        stage: String,
        timeoutMs: Long,
        expectedSocket: BluetoothSocket,
    ): SocketTimeout {
        cancelTimeout()
        val timeout = SocketTimeout(stage, expectedSocket)
        synchronized(timeoutLock) {
            activeTimeout = timeout
        }
        main.postDelayed(timeout, timeoutMs)
        return timeout
    }

    private fun cancelTimeout() {
        val timeout = synchronized(timeoutLock) {
            activeTimeout.also { activeTimeout = null }
        }
        timeout?.let { main.removeCallbacks(it) }
    }

    /** True only when this caller canceled the exact guard before its watchdog won. */
    private fun cancelTimeout(timeout: SocketTimeout): Boolean {
        val canceled = synchronized(timeoutLock) {
            if (activeTimeout === timeout) {
                activeTimeout = null
                true
            } else {
                false
            }
        }
        main.removeCallbacks(timeout)
        return canceled
    }

    private fun postSession(state: SessionState, detail: String? = null) {
        main.post { listener.onSession(state, detail) }
    }

    private fun describeFailure(error: Throwable?): String {
        var cause = error
        while (cause is InvocationTargetException && cause.cause != null) cause = cause.cause
        return when (cause) {
            is NoSuchMethodException -> "This Android build does not expose Classic L2CAP"
            is SecurityException -> "Bluetooth permission or hidden API access was denied"
            null -> "AAP channel closed"
            else -> cause.message?.takeIf { it.isNotBlank() }
                ?: cause.javaClass.simpleName
        }
    }

    private fun dispatchUpdate(update: AapMessageDecoder.Update) {
        main.post {
            when (update) {
                is AapMessageDecoder.Update.Batteries -> listener.onBattery(update.values)
                is AapMessageDecoder.Update.EarPlacement ->
                    listener.onEar(update.primary, update.secondary)
                is AapMessageDecoder.Update.AncMode -> listener.onAncMode(update.wireMode)
                is AapMessageDecoder.Update.IdentityKey -> {
                    Log.i(TAG, "IRK received via key exchange")
                    listener.onIrk(update.value)
                }
            }
        }
    }
}
