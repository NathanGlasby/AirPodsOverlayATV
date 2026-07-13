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
 * hidden connect()/setConnectionPolicy() APIs via reflection, the same approach
 * used by Bluetooth auto-connect apps.
 */
@SuppressLint("MissingPermission")
class ProfileConnector(private val context: Context) {

    sealed class ConnectResult {
        data object Success : ConnectResult()
        data object DeviceNotPaired : ConnectResult()
        data object ProfilesUnavailable : ConnectResult()
        data object RequestRejected : ConnectResult()
        data object TimedOut : ConnectResult()

        val message: String
            get() = when (this) {
                Success -> "Connected"
                DeviceNotPaired -> "AirPods are no longer paired"
                ProfilesUnavailable -> "TV Bluetooth audio service is not ready"
                RequestRejected -> "TV rejected the Bluetooth connection"
                TimedOut -> "Connection timed out"
            }
    }

    companion object {
        private const val TAG = "ProfileConnector"
        private const val CONNECTION_POLICY_ALLOWED = 100
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    private val main = Handler(Looper.getMainLooper())
    private var a2dpRequested = false
    private var headsetRequested = false

    fun open() {
        val ad = adapter
        if (ad == null) {
            return
        }
        if (a2dp == null && !a2dpRequested) {
            a2dpRequested = try {
                ad.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dp = proxy as BluetoothA2dp
                            a2dpRequested = false
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dp = null
                            a2dpRequested = false
                        }
                    }
                }, BluetoothProfile.A2DP)
            } catch (e: Exception) {
                Log.w(TAG, "A2DP getProfileProxy failed", e)
                false
            }
        }
        if (headset == null && !headsetRequested) {
            headsetRequested = try {
                ad.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET) {
                            headset = proxy as BluetoothHeadset
                            headsetRequested = false
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HEADSET) {
                            headset = null
                            headsetRequested = false
                        }
                    }
                }, BluetoothProfile.HEADSET)
            } catch (e: Exception) {
                Log.w(TAG, "Headset getProfileProxy failed", e)
                false
            }
        }
    }

    fun close() {
        a2dpRequested = false
        headsetRequested = false
        main.removeCallbacksAndMessages(null)
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
    fun setAutoConnectAllowed(address: String?, allowed: Boolean): Boolean {
        val device = bondedDevice(address) ?: return false
        val value = if (allowed) CONNECTION_POLICY_ALLOWED else 0
        val proxies = listOf<BluetoothProfile>(a2dp ?: return false, headset ?: return false)
        var allSucceeded = true
        for (proxy in proxies) {
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
                done = ok == true
            } catch (e: Exception) {
                Log.w(TAG, "setPriority failed: $e")
            }
            if (!done) allSucceeded = false
        }
        return allSucceeded
    }

    fun setAutoConnectAllowedWhenReady(
        address: String?,
        allowed: Boolean,
        timeoutMs: Long = 3_000L,
        callback: (Boolean) -> Unit = {},
    ) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        lateinit var attempt: Runnable
        attempt = Runnable {
            when {
                a2dp != null && headset != null -> callback(setAutoConnectAllowed(address, allowed))
                android.os.SystemClock.elapsedRealtime() - startedAt >= timeoutMs -> callback(false)
                else -> {
                    open()
                    main.postDelayed(attempt, 100L)
                }
            }
        }
        main.post(attempt)
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
    fun connect(
        address: String?,
        timeoutMs: Long = 12_000,
        profileReadyTimeoutMs: Long = 3_000,
        callback: (ConnectResult) -> Unit,
    ) {
        val device = bondedDevice(address)
        if (device == null) {
            main.post { callback(ConnectResult.DeviceNotPaired) }
            return
        }
        val readyStartedAt = android.os.SystemClock.elapsedRealtime()
        lateinit var awaitProfiles: Runnable
        awaitProfiles = Runnable {
            when {
                a2dp != null && headset != null -> connectReady(device, address, timeoutMs, callback)
                android.os.SystemClock.elapsedRealtime() - readyStartedAt >= profileReadyTimeoutMs ->
                    callback(ConnectResult.ProfilesUnavailable)
                else -> {
                    open()
                    main.postDelayed(awaitProfiles, 100L)
                }
            }
        }
        main.post(awaitProfiles)
    }

    private fun connectReady(
        device: BluetoothDevice,
        address: String?,
        timeoutMs: Long,
        callback: (ConnectResult) -> Unit,
    ) {
        // A forbidden connection policy makes connect() a no-op, so allow it first.
        setAutoConnectAllowed(address, true)
        var kicked = false
        kicked = connectProfile(a2dp, device) || kicked
        kicked = connectProfile(headset, device) || kicked
        if (!kicked) {
            Log.w(TAG, "No profile connect method worked")
            main.post { callback(ConnectResult.RequestRejected) }
            return
        }
        val start = android.os.SystemClock.elapsedRealtime()
        lateinit var poll: Runnable
        poll = Runnable {
            when {
                isConnected(address) -> callback(ConnectResult.Success)
                android.os.SystemClock.elapsedRealtime() - start > timeoutMs -> callback(ConnectResult.TimedOut)
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
