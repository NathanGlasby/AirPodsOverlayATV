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
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Connects an already-paired Bluetooth classic audio device using the hidden
 * connect()/setConnectionPolicy() APIs. A2DP is the required TV audio path;
 * HFP is used only when the Android TV build exposes a HEADSET profile.
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

    sealed class DisconnectResult {
        data object Success : DisconnectResult()
        data object DeviceNotPaired : DisconnectResult()
        data object ProfilesUnavailable : DisconnectResult()
        data object RequestRejected : DisconnectResult()
        data object TimedOut : DisconnectResult()

        val message: String
            get() = when (this) {
                Success -> "Disconnected"
                DeviceNotPaired -> "AirPods are no longer paired"
                ProfilesUnavailable -> "TV Bluetooth audio service is not ready"
                RequestRejected -> "TV rejected the Bluetooth disconnect request"
                TimedOut -> "Disconnect timed out"
            }
    }

    companion object {
        private const val TAG = "ProfileConnector"
        private const val CONNECTION_POLICY_ALLOWED = 100

        @Volatile
        private var hiddenApiReady = false

        @Synchronized
        private fun ensureHiddenApiAccess(): Boolean {
            if (hiddenApiReady) return true
            return try {
                hiddenApiReady = HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
                hiddenApiReady
            } catch (e: LinkageError) {
                Log.w(TAG, "Could not enable hidden Bluetooth APIs", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable hidden Bluetooth APIs", e)
                false
            }
        }
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    private val main = Handler(Looper.getMainLooper())
    private var a2dpRequested = false
    private var headsetRequested = false

    private data class ConnectionSnapshot(
        val connected: Set<AudioProfilePlan.Profile>,
        val failedQueries: Set<AudioProfilePlan.Profile>,
    )

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
        val snapshot = connectionSnapshot(device, availableProfiles().keys)
        return AudioProfilePlan.isAudioConnected(snapshot.connected)
    }

    private fun availableProfiles(): Map<AudioProfilePlan.Profile, BluetoothProfile> = buildMap {
        a2dp?.let { put(AudioProfilePlan.Profile.A2DP, it) }
        headset?.let { put(AudioProfilePlan.Profile.HEADSET, it) }
    }

    private fun connectionSnapshot(
        device: BluetoothDevice,
        targets: Set<AudioProfilePlan.Profile>,
    ): ConnectionSnapshot {
        val available = availableProfiles()
        val connected = mutableSetOf<AudioProfilePlan.Profile>()
        val failed = mutableSetOf<AudioProfilePlan.Profile>()
        for (profile in targets) {
            val proxy = available[profile]
            if (proxy == null) {
                if (AudioProfilePlan.missingProxyBlocksDisconnect(profile)) failed += profile
                continue
            }
            try {
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    connected += profile
                }
            } catch (e: Exception) {
                Log.w(TAG, "$profile getConnectionState failed", e)
                failed += profile
            }
        }
        return ConnectionSnapshot(connected, failed)
    }

    /**
     * Sets the per-device connection policy/priority for A2DP and, when present, HFP.
     * allowed=false makes the OS refuse auto-connections (incoming and outgoing)
     * for this device until re-allowed. Note: forbidding while connected causes
     * the OS to disconnect the device.
     */
    fun setAutoConnectAllowed(
        address: String?,
        allowed: Boolean,
        optionalProfileWaitExpired: Boolean = false,
    ): Boolean {
        if (!ensureHiddenApiAccess()) return false
        val device = bondedDevice(address) ?: return false
        val value = if (allowed) CONNECTION_POLICY_ALLOWED else 0
        val available = availableProfiles()
        if (!AudioProfilePlan.canApplyPolicy(
                available = available.keys,
                optionalProfilePending = headsetRequested,
                waitExpired = optionalProfileWaitExpired,
            )
        ) {
            Log.w(TAG, "Policy change deferred while audio profiles are still binding")
            return false
        }
        val targets = AudioProfilePlan.operationTargets(available.keys)
        var allSucceeded = true
        for (profile in targets) {
            val proxy = available.getValue(profile)
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
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            val available = availableProfiles()
            val waitExpired = elapsed >= timeoutMs
            when {
                AudioProfilePlan.canApplyPolicy(
                    available = available.keys,
                    optionalProfilePending = headsetRequested,
                    waitExpired = waitExpired,
                ) ->
                    callback(
                        setAutoConnectAllowed(
                            address = address,
                            allowed = allowed,
                            optionalProfileWaitExpired = waitExpired,
                        )
                    )
                waitExpired -> callback(false)
                else -> {
                    open()
                    main.postDelayed(attempt, 100L)
                }
            }
        }
        main.post(attempt)
    }

    /** Disconnects every currently available TV audio profile. */
    fun disconnect(address: String?): Boolean {
        if (!ensureHiddenApiAccess()) return false
        val device = bondedDevice(address) ?: return false
        val targets = AudioProfilePlan.operationTargets(availableProfiles().keys).toSet()
        return disconnectTargets(device, targets)
    }

    private fun disconnectTargets(
        device: BluetoothDevice,
        targets: Set<AudioProfilePlan.Profile>,
    ): Boolean {
        val available = availableProfiles()
        var requested = false
        for (profile in targets) {
            val proxy = available[profile] ?: continue
            try {
                val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                m.isAccessible = true
                val ok = m.invoke(proxy, device)
                Log.i(TAG, "${proxy.javaClass.simpleName}.disconnect() -> $ok")
                requested = ok == true || requested
            } catch (e: Exception) {
                Log.w(TAG, "disconnect reflection failed: $e")
            }
        }
        return requested
    }

    fun disconnectWhenReady(
        address: String?,
        timeoutMs: Long = 8_000L,
        profileReadyTimeoutMs: Long = 3_000L,
        callback: (DisconnectResult) -> Unit,
    ) {
        if (bondedDevice(address) == null) {
            main.post { callback(DisconnectResult.DeviceNotPaired) }
            return
        }
        val readyStartedAt = android.os.SystemClock.elapsedRealtime()
        lateinit var awaitProfiles: Runnable
        awaitProfiles = Runnable {
            val elapsed = android.os.SystemClock.elapsedRealtime() - readyStartedAt
            val available = availableProfiles()
            when {
                AudioProfilePlan.canApplyPolicy(
                    available = available.keys,
                    optionalProfilePending = headsetRequested,
                    waitExpired = elapsed >= profileReadyTimeoutMs,
                ) -> disconnectReady(address, available.keys, timeoutMs, callback)
                elapsed >= profileReadyTimeoutMs ->
                    callback(DisconnectResult.ProfilesUnavailable)
                else -> {
                    open()
                    main.postDelayed(awaitProfiles, 100L)
                }
            }
        }
        main.post(awaitProfiles)
    }

    private fun disconnectReady(
        address: String?,
        available: Set<AudioProfilePlan.Profile>,
        timeoutMs: Long,
        callback: (DisconnectResult) -> Unit,
    ) {
        val device = bondedDevice(address)
        if (device == null) {
            callback(DisconnectResult.DeviceNotPaired)
            return
        }
        val targets = AudioProfilePlan.operationTargets(available).toSet()
        val initial = connectionSnapshot(device, targets)
        if (AudioProfilePlan.isDisconnectComplete(
                targets,
                initial.connected,
                initial.failedQueries,
            )
        ) {
            callback(DisconnectResult.Success)
            return
        }
        if (!disconnectTargets(device, targets)) {
            callback(DisconnectResult.RequestRejected)
            return
        }

        val startedAt = android.os.SystemClock.elapsedRealtime()
        var retried = false
        lateinit var poll: Runnable
        poll = Runnable {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            val snapshot = connectionSnapshot(device, targets)
            when {
                AudioProfilePlan.isDisconnectComplete(
                    targets,
                    snapshot.connected,
                    snapshot.failedQueries,
                ) -> callback(DisconnectResult.Success)
                elapsed >= timeoutMs -> callback(DisconnectResult.TimedOut)
                !retried && elapsed >= timeoutMs / 2 -> {
                    retried = true
                    disconnectTargets(device, targets)
                    main.postDelayed(poll, 250L)
                }
                else -> main.postDelayed(poll, 250L)
            }
        }
        main.postDelayed(poll, 250L)
    }

    /**
     * Connects the A2DP audio path and attempts HFP as a best effort when available.
     * Reports success only after A2DP reaches the connected state.
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
            val elapsed = android.os.SystemClock.elapsedRealtime() - readyStartedAt
            val available = availableProfiles()
            val waitExpired = elapsed >= profileReadyTimeoutMs
            when {
                AudioProfilePlan.canApplyPolicy(
                    available = available.keys,
                    optionalProfilePending = headsetRequested,
                    waitExpired = waitExpired,
                ) ->
                    connectReady(
                        device = device,
                        address = address,
                        timeoutMs = timeoutMs,
                        optionalProfileWaitExpired = waitExpired,
                        callback = callback,
                    )
                waitExpired ->
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
        optionalProfileWaitExpired: Boolean,
        callback: (ConnectResult) -> Unit,
    ) {
        val audioProxy = a2dp
        if (audioProxy == null) {
            callback(ConnectResult.ProfilesUnavailable)
            return
        }
        // A forbidden connection policy makes connect() a no-op, so allow it first.
        setAutoConnectAllowed(
            address = address,
            allowed = true,
            optionalProfileWaitExpired = optionalProfileWaitExpired,
        )
        val audioRequested = connectProfile(audioProxy, device)
        headset?.let { connectProfile(it, device) }
        if (!audioRequested) {
            Log.w(TAG, "No A2DP connect method worked")
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
        if (!ensureHiddenApiAccess()) return false
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
