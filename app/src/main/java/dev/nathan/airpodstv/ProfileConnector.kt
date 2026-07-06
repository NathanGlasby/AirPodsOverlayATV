package dev.nathan.airpodstv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Connects an already-paired Bluetooth classic audio device (A2DP + HFP) using the
 * hidden connect()/setConnectionPolicy() APIs via reflection — the same approach
 * used by Bluetooth auto-connect apps.
 */
@SuppressLint("MissingPermission")
class ProfileConnector(private val context: Context) {

    companion object {
        private const val TAG = "ProfileConnector"
        private const val CONNECTION_POLICY_ALLOWED = 100
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    private val main = Handler(Looper.getMainLooper())

    fun open() {
        val ad = adapter ?: return
        try {
            ad.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) a2dp = proxy as BluetoothA2dp
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dp = null
                }
            }, BluetoothProfile.A2DP)
            ad.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) headset = proxy as BluetoothHeadset
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) headset = null
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.w(TAG, "getProfileProxy failed", e)
        }
    }

    fun close() {
        try {
            a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Exception) {
        }
        a2dp = null
        headset = null
    }

    fun bondedDevice(address: String?): BluetoothDevice? {
        if (address == null) return null
        return try {
            adapter?.bondedDevices?.firstOrNull { it.address == address }
        } catch (e: Exception) {
            Log.w(TAG, "bondedDevices failed", e)
            null
        }
    }

    fun isConnected(address: String?): Boolean {
        val device = bondedDevice(address) ?: return false
        return try {
            a2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED ||
                headset?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sets the per-device connection policy/priority for both audio profiles.
     * allowed=false makes the OS refuse auto-connections (incoming and outgoing)
     * for this device until re-allowed. Note: forbidding while connected causes
     * the OS to disconnect the device.
     */
    fun setAutoConnectAllowed(address: String?, allowed: Boolean) {
        val device = bondedDevice(address) ?: return
        val value = if (allowed) CONNECTION_POLICY_ALLOWED else 0
        for (proxy in listOfNotNull<BluetoothProfile>(a2dp, headset)) {
            var done = false
            try {
                val m = proxy.javaClass.getMethod(
                    "setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType
                )
                m.isAccessible = true
                val ok = m.invoke(proxy, device, value)
                Log.i(TAG, "${proxy.javaClass.simpleName}.setConnectionPolicy($value) -> $ok")
                done = ok == true
            } catch (e: Exception) {
                Log.w(TAG, "setConnectionPolicy failed: $e")
            }
            if (done) continue
            // Older name for the same knob (Android <= 11 uses priority).
            try {
                val m = proxy.javaClass.getMethod(
                    "setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType
                )
                m.isAccessible = true
                val ok = m.invoke(proxy, device, value)
                Log.i(TAG, "${proxy.javaClass.simpleName}.setPriority($value) -> $ok")
            } catch (e: Exception) {
                Log.w(TAG, "setPriority failed: $e")
            }
        }
    }

    /** Disconnects both audio profiles via the hidden disconnect(BluetoothDevice). */
    fun disconnect(address: String?) {
        val device = bondedDevice(address) ?: return
        for (proxy in listOfNotNull<BluetoothProfile>(a2dp, headset)) {
            try {
                val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                m.isAccessible = true
                val ok = m.invoke(proxy, device)
                Log.i(TAG, "${proxy.javaClass.simpleName}.disconnect() -> $ok")
            } catch (e: Exception) {
                Log.w(TAG, "disconnect reflection failed: $e")
            }
        }
    }

    /**
     * Kicks off connection to both audio profiles; polls the connection state and
     * reports success/failure on the main thread within [timeoutMs].
     */
    fun connect(address: String?, timeoutMs: Long = 12_000, callback: (Boolean) -> Unit) {
        val device = bondedDevice(address)
        if (device == null) {
            main.post { callback(false) }
            return
        }
        // A forbidden connection policy makes connect() a no-op — allow first.
        setAutoConnectAllowed(address, true)
        var kicked = false
        kicked = connectProfile(a2dp, device) || kicked
        kicked = connectProfile(headset, device) || kicked
        if (!kicked) {
            Log.w(TAG, "No profile connect method worked")
            main.post { callback(false) }
            return
        }
        val start = System.currentTimeMillis()
        lateinit var poll: Runnable
        poll = Runnable {
            when {
                isConnected(address) -> callback(true)
                System.currentTimeMillis() - start > timeoutMs -> callback(false)
                else -> main.postDelayed(poll, 500)
            }
        }
        main.postDelayed(poll, 500)
    }

    private fun connectProfile(proxy: BluetoothProfile?, device: BluetoothDevice): Boolean {
        if (proxy == null) return false
        // Try the classic hidden connect(BluetoothDevice) first.
        try {
            val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            m.isAccessible = true
            val ok = m.invoke(proxy, device)
            Log.i(TAG, "${proxy.javaClass.simpleName}.connect() -> $ok")
            if (ok == true) return true
        } catch (e: Exception) {
            Log.w(TAG, "connect() reflection failed: $e")
        }
        // Fall back to setConnectionPolicy(device, ALLOWED) which triggers auto-connect.
        try {
            val m = proxy.javaClass.getMethod(
                "setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            val ok = m.invoke(proxy, device, CONNECTION_POLICY_ALLOWED)
            Log.i(TAG, "${proxy.javaClass.simpleName}.setConnectionPolicy() -> $ok")
            if (ok == true) return true
        } catch (e: Exception) {
            Log.w(TAG, "setConnectionPolicy reflection failed: $e")
        }
        return false
    }
}
