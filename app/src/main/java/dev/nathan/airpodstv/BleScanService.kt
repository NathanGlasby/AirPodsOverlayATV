package dev.nathan.airpodstv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Foreground service that scans for AirPods proximity-pairing beacons and drives
 * the connect popup. Lid-open shows the popup; lid-close (or beacons stopping)
 * dismisses it.
 */
@SuppressLint("MissingPermission")
class BleScanService : Service() {

    companion object {
        private const val TAG = "BleScanService"
        private const val CHANNEL_ID = "scanner"
        private const val NOTIF_ID = 1

        const val ACTION_START = "dev.nathan.airpodstv.START"
        const val ACTION_STOP = "dev.nathan.airpodstv.STOP"
        const val ACTION_TEST_POPUP = "dev.nathan.airpodstv.TEST_POPUP"
        const val ACTION_APPLY_POLICY = "dev.nathan.airpodstv.APPLY_POLICY"
        const val ACTION_AAP_CHANGED = "dev.nathan.airpodstv.AAP_CHANGED"
        const val ACTION_DEVICE_CHANGED = "dev.nathan.airpodstv.DEVICE_CHANGED"
        const val ACTION_RESTORE_POLICY = "dev.nathan.airpodstv.RESTORE_POLICY"
        private const val EXTRA_OLD_DEVICE_ADDRESS = "old_device_address"

        /** Connections within this window of a popup Connect are considered user-initiated. */
        private const val USER_CONNECT_WINDOW_MS = 30_000L

        /** How long without a strong beacon before we assume the case closed. */
        private const val BEACON_GONE_MS = 5_000L

        /** After dismiss/connect, require beacons to vanish this long before a new popup. */
        private const val SESSION_RESET_MS = 8_000L

        /** Back off briefly after Bluetooth is unavailable or Android rejects a scan. */
        private const val SCAN_RETRY_MS = 5_000L

        /** Give the controller a quiet interval between BLE scanning and Classic L2CAP. */
        private const val AAP_RADIO_SETTLE_MS = 750L
        private const val AAP_SCAN_RESUME_DELAY_MS = 500L

        private const val LID_DISCONNECT_RETRY_MS = 2_000L
        private const val LID_DISCONNECT_MAX_ATTEMPTS = 3

        var isRunning = false
            private set

        var instance: BleScanService? = null
            private set

        /** AirPods 4 (ANC) model id. The model filter reacts only to this. */
        private const val MODEL_AIRPODS_4_ANC = 0x1B20

        fun start(context: Context, action: String = ACTION_START): Boolean {
            return try {
                val i = Intent(context, BleScanService::class.java).setAction(action)
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start foreground scanner", e)
                Prefs(context).serviceStatus = "Scanner could not start: ${e.javaClass.simpleName}"
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleScanService::class.java))
        }

        fun deviceChanged(context: Context, oldAddress: String?) {
            val i = Intent(context, BleScanService::class.java)
                .setAction(ACTION_DEVICE_CHANGED)
                .putExtra(EXTRA_OLD_DEVICE_ADDRESS, oldAddress)
            context.startForegroundService(i)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var connector: ProfileConnector
    private var overlay: OverlayController? = null
    private var scanner: BluetoothLeScanner? = null
    private val main = Handler(Looper.getMainLooper())

    private val popupSession = PopupSession(SESSION_RESET_MS)
    private val aapSessionPolicy = AapSessionPolicy()
    private val earPausePolicy = EarPausePolicy()
    private val lidClosePolicy = LidClosePolicy(BEACON_GONE_MS)
    private val connectionAttempts = ConnectionAttemptTracker(USER_CONNECT_WINDOW_MS)
    private var popupIsTest = false
    private var latestBeacon: BeaconParser.Beacon? = null
    private var latestAcceptedBeacon: BeaconParser.Beacon? = null
    private var irkBytes: ByteArray? = null
    private var identityKeyVerified = false
    private var nextScanRetryAt = 0L
    private var aap: AapClient? = null
    private var aapDeviceAddress: String? = null
    private var aapGeneration = 0L
    private var aapTransportInFlight = false
    private var aapTransportUnsupported = false
    private var pendingAapStart: Runnable? = null
    private var scanResumeAt = 0L
    private var aapEarAuthoritative = false
    private var aapBothPodsInCase = false
    private var earStreakValue: Int? = null
    private var earStreak = 0
    private var disconnectInFlight = false
    private var lidDisconnectAttempts = 0
    private var lidCloseCycleGeneration = 0L
    private var podsOutsideCaseStreak = 0
    private var silenceChecksResumeAt = 0L
    private var connectedSessionInitialized = false

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val address = device?.address ?: return
            if (!address.equals(prefs.deviceAddress, ignoreCase = true)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val userInitiated = connectionAttempts.onAclConnected(
                        address,
                        SystemClock.elapsedRealtime(),
                    )
                    if (prefs.blockAutoConnect && !userInitiated) {
                        Log.i(TAG, "OS auto-connected $address; blocking")
                        // Forbidding the policy also makes the OS drop the link.
                        if (connector.setAutoConnectAllowed(address, false)) {
                            prefs.connectionPolicyBlocked = true
                        }
                        connector.disconnect(address)
                    } else {
                        onDeviceConnected()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connectionAttempts.onAclDisconnected(
                        address,
                        SystemClock.elapsedRealtime(),
                    )
                    aapTransportUnsupported = false
                    stopAap(AapBus.SessionState.WAITING_FOR_CONNECTION)
                    resetConnectedReactions()
                    // Re-arm the block once the user-approved session ends.
                    if (prefs.blockAutoConnect) {
                        main.postDelayed({
                            if (address.equals(prefs.deviceAddress, ignoreCase = true) &&
                                connector.setAutoConnectAllowed(address, false)
                            ) {
                                prefs.connectionPolicyBlocked = true
                            }
                        }, 1500L)
                    }
                }
            }
        }
    }

    /** FORBIDDEN would kick an active session, so only apply the block while disconnected. */
    private fun applyConnectionPolicy() {
        val addr = prefs.deviceAddress ?: return
        when (ConnectionPolicyPlan.decide(prefs.blockAutoConnect, connector.isConnected(addr))) {
            ConnectionPolicyPlan.Action.BLOCK_WHEN_READY -> {
                connector.setAutoConnectAllowedWhenReady(addr, false) { ok ->
                    prefs.connectionPolicyBlocked = ok
                    prefs.serviceStatus = if (ok) "TV auto-connect is blocked" else
                        "Could not change the TV auto-connect policy"
                }
            }
            ConnectionPolicyPlan.Action.RESTORE_WHEN_READY -> {
                connector.setAutoConnectAllowedWhenReady(addr, true) { ok ->
                    if (ok) prefs.connectionPolicyBlocked = false
                    prefs.serviceStatus = if (ok) "TV auto-connect is restored" else
                        "Could not restore the TV auto-connect policy"
                }
            }
            ConnectionPolicyPlan.Action.DEFER_UNTIL_DISCONNECTED ->
                prefs.serviceStatus = "TV auto-connect block deferred until disconnect"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            reconcileAapSession(now)
            val silenceChecksAllowed = !aapTransportInFlight && scanner != null &&
                now >= silenceChecksResumeAt
            if (silenceChecksAllowed &&
                prefs.autoDisconnectOnLidClose &&
                connector.isConnected(prefs.deviceAddress) &&
                lidClosePolicy.onTime(now) == LidClosePolicy.Action.DISCONNECT
            ) {
                disconnectSelectedDevice("case beacon stopped after both pods entered the case")
            }
            val lastBeaconAt = popupSession.lastBeaconAt
            if (silenceChecksAllowed && overlay?.isShowing == true && !popupIsTest &&
                lastBeaconAt != 0L &&
                now - lastBeaconAt > BEACON_GONE_MS
            ) {
                Log.i(TAG, "Beacons stopped; dismissing popup")
                dismissPopup()
            }
            if (silenceChecksAllowed && popupSession.rearmIfSilent(now)) {
                Log.i(TAG, "Session reset; popup re-armed")
            }
            // Long silence: next case-open is a fresh session.
            if (silenceChecksAllowed) popupSession.clearAfterLongSilence(now, 60_000L)
            if (scanner == null && now >= nextScanRetryAt) startScan()
            main.postDelayed(this, 1000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val beacon = BeaconParser.parse(result)
            if (beacon != null) {
                latestBeacon = beacon
                val decision = BeaconGate.evaluate(
                    beacon = beacon,
                    modelFilter = prefs.modelFilter,
                    requiredModel = MODEL_AIRPODS_4_ANC,
                    identityFilter = prefs.identityFilter,
                    irk = irkBytes,
                    identityKeyVerified = identityKeyVerified,
                    rssiThreshold = prefs.rssiThreshold,
                )
                if (decision.identityMatched && !identityKeyVerified) {
                    identityKeyVerified = true
                    prefs.irkVerified = true
                    Log.i(TAG, "Captured identity key verified against a live beacon")
                    AapBus.notifyChanged()
                }
                if (decision.passes) latestAcceptedBeacon = beacon
                BeaconBus.publish(beacon, decision.passes, decision.reason.label)
                if (isConnectedReactionBeacon(beacon, decision)) handleConnectedBeacon(beacon)
                if (decision.passes) handlePopupBeacon(beacon)
                return
            }
            // This is not a proximity-pairing frame. Surface other Apple frame types so we
            // can see what the case actually broadcasts on lid-open.
            val data = result.scanRecord
                ?.getManufacturerSpecificData(BeaconParser.APPLE_COMPANY_ID) ?: return
            if (data.isEmpty()) return
            val type = data[0].toInt() and 0xFF
            val hex = data.take(16).joinToString("") { "%02X".format(it) }
            BeaconBus.publishRaw(type, hex, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanner = null
            // Scanner failure is not case silence; discard the stale lid-close latch.
            lidClosePolicy.reset()
            podsOutsideCaseStreak = 0
            prefs.serviceStatus = "BLE scan failed (code $errorCode); retrying"
            nextScanRetryAt = SystemClock.elapsedRealtime() + SCAN_RETRY_MS
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        AapBus.notifyChanged()
        prefs = Prefs(this)
        refreshIrk()
        connector = ProfileConnector(this)
        connector.open()
        prefs.serviceStatus = "Scanner starting"
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(aclReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
        // Profile proxies bind asynchronously; apply the policy once they're up.
        main.postDelayed({ applyConnectionPolicy() }, 3000L)
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_POPUP -> {
                main.postDelayed({ showPopup(testMode = true) }, 3000L)
            }
            ACTION_APPLY_POLICY -> {
                applyConnectionPolicy()
            }
            ACTION_AAP_CHANGED -> {
                aapTransportUnsupported = false
                if (!prefs.aapEnabled) {
                    stopAap(AapBus.SessionState.DISABLED)
                } else {
                    reconcileAapSession(SystemClock.elapsedRealtime())
                }
            }
            ACTION_DEVICE_CHANGED -> {
                connectionAttempts.cancelAll()
                aapTransportUnsupported = false
                val oldAddress = intent.getStringExtra(EXTRA_OLD_DEVICE_ADDRESS)
                if (prefs.blockAutoConnect && oldAddress != null) {
                    if (connector.setAutoConnectAllowed(oldAddress, true)) {
                        prefs.connectionPolicyBlocked = false
                    }
                }
                stopAap(
                    if (prefs.aapEnabled) AapBus.SessionState.WAITING_FOR_CONNECTION
                    else AapBus.SessionState.DISABLED
                )
                popupSession.reset()
                dismissPopup()
                latestBeacon = null
                latestAcceptedBeacon = null
                resetConnectedReactions()
                main.postDelayed({ applyConnectionPolicy() }, 500L)
            }
            ACTION_RESTORE_POLICY -> {
                prefs.blockAutoConnect = false
                applyConnectionPolicy()
                if (!prefs.enabled) main.postDelayed({ stopSelf() }, 4_000L)
            }
        }
        refreshIrk()
        startScan()
        return START_STICKY
    }

    private fun refreshIrk() {
        val selectedAddress = prefs.deviceAddress
        val storedIrk = prefs.irkHex
        if (storedIrk != null && prefs.irkDeviceAddress == null && selectedAddress != null) {
            // One-time migration from versions that stored a global, unscoped key.
            prefs.storeIdentityKey(selectedAddress, storedIrk)
        } else if (storedIrk != null && prefs.irkDeviceAddress != selectedAddress) {
            Log.w(TAG, "Discarding identity key that belongs to another selected device")
            prefs.clearIdentity()
        }
        irkBytes = prefs.irkHex?.let {
            try {
                RpaVerifier.fromHex(it)
            } catch (e: Exception) {
                null
            }
        }
        identityKeyVerified = irkBytes != null && prefs.irkVerified
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        // Shutdown invalidates confirmed attempts too; dismissPopup only cancels pending work.
        connectionAttempts.cancelAll()
        stopAap(AapBus.SessionState.WAITING_FOR_CONNECTION)
        main.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(aclReceiver)
        } catch (_: Exception) {
        }
        stopScan()
        dismissPopup()
        // With the app inactive there is no popup to connect through. Restore
        // stock auto-connect behavior rather than leaving the AirPods blocked.
        if (prefs.blockAutoConnect) {
            if (connector.setAutoConnectAllowed(prefs.deviceAddress, true)) {
                prefs.connectionPolicyBlocked = false
            }
        }
        connector.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startScan() {
        if (scanner != null || aapTransportInFlight ||
            SystemClock.elapsedRealtime() < scanResumeAt
        ) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off; cannot scan")
            nextScanRetryAt = SystemClock.elapsedRealtime() + SCAN_RETRY_MS
            return
        }
        val bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            Log.w(TAG, "BLE scanner unavailable; will retry")
            nextScanRetryAt = SystemClock.elapsedRealtime() + SCAN_RETRY_MS
            return
        }
        scanner = bleScanner
        // Match ANY Apple manufacturer frame; we sort out message types in the callback.
        val filter = ScanFilter.Builder()
            .setManufacturerData(BeaconParser.APPLE_COMPANY_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            nextScanRetryAt = 0L
            // A recovered scanner needs a fresh observation window before absence is
            // meaningful. Otherwise an old armed case sample can cause a false disconnect.
            silenceChecksResumeAt = maxOf(
                silenceChecksResumeAt,
                SystemClock.elapsedRealtime() + BEACON_GONE_MS,
            )
            prefs.serviceStatus = "BLE scan is active"
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            scanner = null
            prefs.serviceStatus = "BLE scan could not start: ${e.javaClass.simpleName}"
            nextScanRetryAt = SystemClock.elapsedRealtime() + SCAN_RETRY_MS
        }
    }

    private fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanner = null
    }

    private fun isConnectedReactionBeacon(
        beacon: BeaconParser.Beacon,
        popupDecision: BeaconGate.Decision,
    ): Boolean {
        if (!connector.isConnected(prefs.deviceAddress)) return false
        val key = irkBytes
        if (identityKeyVerified && key != null) return RpaVerifier.verify(beacon.address, key)

        // AirPods rotate and may interleave case/pod BLE addresses. Before the IRK is
        // available, the same model/proximity gate shown by the green debug marker owns
        // the already-debounced reaction path; a hidden address lock made green frames inert.
        return popupDecision.passes
    }

    private fun handleConnectedBeacon(beacon: BeaconParser.Beacon) {
        val now = SystemClock.elapsedRealtime()

        // AAP placement is transition-driven, so silence does not make it stale. BLE is
        // only a fallback until the first complete AAP placement sample in this session.
        if (!aapEarAuthoritative) {
            val inEarCount = listOf(beacon.leftInEar, beacon.rightInEar).count { it }
            handleEarCount(inEarCount, requireStreak = true)
        }

        // AAP owns pod placement after its first complete sample. BLE may then add the
        // reliable lid-closed edge, but it may not override AAP's in/out-of-case state.
        val lidAction = when {
            aapEarAuthoritative && aapBothPodsInCase -> {
                podsOutsideCaseStreak = 0
                lidClosePolicy.onSignal(
                    bothPodsInCase = true,
                    explicitlyClosed = beacon.lidState == BeaconParser.LidState.CLOSED,
                    nowMs = now,
                )
            }
            aapEarAuthoritative -> LidClosePolicy.Action.NONE
            beacon.bothPodsInCase -> {
                podsOutsideCaseStreak = 0
                lidClosePolicy.onSignal(
                    bothPodsInCase = true,
                    explicitlyClosed = beacon.lidState == BeaconParser.LidState.CLOSED,
                    nowMs = now,
                )
            }
            else -> {
                podsOutsideCaseStreak++
                if (podsOutsideCaseStreak == 2) {
                    resetLidCloseCycle()
                    // Keep the confirmed state saturated until a both-in-case frame returns.
                    podsOutsideCaseStreak = 2
                }
                LidClosePolicy.Action.NONE
            }
        }
        if (prefs.autoDisconnectOnLidClose && lidAction == LidClosePolicy.Action.DISCONNECT) {
            disconnectSelectedDevice("case lid closed with both pods inside")
        }
    }

    private fun handlePopupBeacon(beacon: BeaconParser.Beacon) {
        val now = SystemClock.elapsedRealtime()

        when (popupSession.onBeacon(beacon.lidState, now)) {
            PopupSession.Event.LID_CLOSED -> {
                if (overlay?.isShowing == true) {
                    Log.i(TAG, "Lid closed; dismissing popup")
                    dismissPopup()
                }
            }
            PopupSession.Event.OFFER_POPUP -> {
                if (overlay?.isShowing != true) {
                    if (connector.isConnected(prefs.deviceAddress)) {
                        // Already connected; nothing to offer.
                        popupSession.suppress()
                        return
                    }
                    Log.i(TAG, "AirPods wake/open session; showing popup")
                    showPopup()
                }
            }
            PopupSession.Event.NONE -> {
            }
        }
    }

    private fun showPopup(testMode: Boolean = false) {
        if (overlay?.isShowing == true) return
        popupIsTest = testMode
        val name = prefs.displayName ?: latestBeacon?.modelName ?: "AirPods"
        val ov = OverlayController(
            this,
            onConnect = { connectFromPopup() },
            onDismiss = {
                dismissPopup()
                popupSession.suppress()
            },
        )
        overlay = ov
        if (!ov.show(name, prefs.popupTimeoutSec)) {
            overlay = null
            prefs.serviceStatus = "Overlay permission is unavailable"
            return
        }
        if (!testMode && prefs.autoConnect) {
            ov.setConnecting()
            connectFromPopup()
        }
    }

    private fun connectFromPopup() {
        val address = prefs.deviceAddress?.takeIf { it.isNotBlank() }
        if (address == null) {
            val message = "No paired AirPods are selected"
            overlay?.setResult(success = false, message = message)
            prefs.serviceStatus = message
            return
        }
        val token = connectionAttempts.begin(address, SystemClock.elapsedRealtime())
        connector.connect(address) { result ->
            val ok = result is ProfileConnector.ConnectResult.Success
            val accepted = connectionAttempts.acceptCallback(
                token = token,
                selectedAddress = prefs.deviceAddress,
                success = ok,
                nowMs = SystemClock.elapsedRealtime(),
            )
            if (!accepted) {
                Log.i(TAG, "Ignoring stale connection result for $address")
            } else {
                if (ok) prefs.connectionPolicyBlocked = false
                overlay?.setResult(ok, message = result.message)
                prefs.serviceStatus = result.message
                if (ok) {
                    popupSession.suppress()
                    onDeviceConnected()
                }
            }
        }
    }

    private fun dismissPopup() {
        connectionAttempts.cancelPending()
        overlay?.hide()
        overlay = null
    }

    // ---- reactions ----

    /** BLE counts need two identical frames; AAP placement updates are trusted directly. */
    private fun handleEarCount(inEarCount: Int, requireStreak: Boolean) {
        if (!prefs.autoPause) {
            earPausePolicy.reset()
            return
        }
        if (!connector.isConnected(prefs.deviceAddress)) {
            earPausePolicy.reset()
            return
        }
        if (requireStreak) {
            if (inEarCount == earStreakValue) earStreak++ else {
                earStreakValue = inEarCount
                earStreak = 1
            }
            if (earStreak < 2) return
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val action = earPausePolicy.onInEarCount(
            inEarCount,
            playbackActive = am.isMusicActive,
        )
        val key = when (action) {
            EarPausePolicy.Action.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            EarPausePolicy.Action.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            EarPausePolicy.Action.NONE -> return
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        Log.i(TAG, if (action == EarPausePolicy.Action.PLAY) "Pod returned; play" else "Pod removed; pause")
    }

    private fun onDeviceConnected() {
        if (!connectedSessionInitialized) {
            connectedSessionInitialized = true
            resetConnectedReactions(keepConnectionFlag = true)
        }
        reconcileAapSession(SystemClock.elapsedRealtime())
        if (overlay?.isShowing != true && Settings.canDrawOverlays(this)) {
            val ov = OverlayController(this, onConnect = {}, onDismiss = { dismissPopup() })
            overlay = ov
            if (ov.show(prefs.displayName ?: "AirPods", 4, withButtons = false)) {
                ov.setSubtitle("Connected", 0xFF64D987.toInt())
            } else {
                overlay = null
                prefs.serviceStatus = "Connected; overlay permission is unavailable"
            }
        }
    }

    // ---- AAP session ----

    fun sendAncMode(wireMode: Int) {
        aap?.setAncMode(wireMode)
    }

    private fun reconcileAapSession(now: Long) {
        val connected = !disconnectInFlight && connector.isConnected(prefs.deviceAddress)
        if (aapTransportUnsupported) {
            if (prefs.aapEnabled && connected) {
                updateAapState(
                    AapBus.SessionState.UNSUPPORTED,
                    AapBus.sessionDetail
                        ?: "both Classic L2CAP socket modes timed out on this Android 11 build",
                )
                return
            }
            aapTransportUnsupported = false
        }
        when (aapSessionPolicy.decide(
            enabled = prefs.aapEnabled,
            connected = connected,
            clientPresent = aap != null,
            nowMs = now,
        )) {
            AapSessionPolicy.Action.START -> startAap()
            AapSessionPolicy.Action.STOP -> stopAap(
                if (prefs.aapEnabled) AapBus.SessionState.WAITING_FOR_CONNECTION
                else AapBus.SessionState.DISABLED
            )
            AapSessionPolicy.Action.NONE -> {
                if (aap == null) {
                    when {
                        !prefs.aapEnabled -> updateAapState(AapBus.SessionState.DISABLED)
                        !connected -> updateAapState(AapBus.SessionState.WAITING_FOR_CONNECTION)
                        aapSessionPolicy.retryDeadlineMs != null ->
                            updateAapState(AapBus.SessionState.RETRYING, AapBus.sessionDetail)
                    }
                }
            }
        }
    }

    private fun startAap() {
        if (aap != null || aapTransportUnsupported) return
        val address = prefs.deviceAddress ?: return
        if (!connector.isConnected(address)) return
        val device = connector.bondedDevice(address) ?: return
        val generation = ++aapGeneration
        aapDeviceAddress = address
        Log.i(TAG, "Starting AAP session")
        val client = AapClient(device, createAapListener(generation, address))
        aap = client
        aapTransportInFlight = true
        scanResumeAt = Long.MAX_VALUE
        silenceChecksResumeAt = Long.MAX_VALUE
        stopScan()
        updateAapState(AapBus.SessionState.CONNECTING, "quieting Bluetooth radio")
        val delayedStart = Runnable {
            pendingAapStart = null
            if (!isCurrentAapClient(generation, address) ||
                aap !== client ||
                !connector.isConnected(address)
            ) {
                if (aap === client) stopAap(AapBus.SessionState.WAITING_FOR_CONNECTION)
                else finishAapTransportAttempt()
                return@Runnable
            }
            client.start()
        }
        pendingAapStart = delayedStart
        main.postDelayed(delayedStart, AAP_RADIO_SETTLE_MS)
    }

    private fun stopAap(targetState: AapBus.SessionState) {
        ++aapGeneration
        pendingAapStart?.let { main.removeCallbacks(it) }
        pendingAapStart = null
        val client = aap
        aapDeviceAddress = null
        aap = null
        client?.stop()
        aapSessionPolicy.reset()
        aapEarAuthoritative = false
        aapBothPodsInCase = false
        updateAapState(targetState)
        AapBus.batteryLine = null
        AapBus.ancMode = null
        AapBus.notifyChanged()
        finishAapTransportAttempt()
    }

    private fun finishAapTransportAttempt() {
        pendingAapStart?.let { main.removeCallbacks(it) }
        pendingAapStart = null
        val shouldResumeScan = aapTransportInFlight
        aapTransportInFlight = false
        if (shouldResumeScan && isRunning) {
            val now = SystemClock.elapsedRealtime()
            scanResumeAt = now + AAP_SCAN_RESUME_DELAY_MS
            silenceChecksResumeAt = scanResumeAt + BEACON_GONE_MS
            main.postDelayed({
                if (isRunning && !aapTransportInFlight && scanner == null) startScan()
            }, AAP_SCAN_RESUME_DELAY_MS)
        }
    }

    private fun isCurrentAapClient(generation: Long, address: String): Boolean =
        generation == aapGeneration && address == aapDeviceAddress && address == prefs.deviceAddress

    private fun createAapListener(generation: Long, address: String) = object : AapClient.Listener {
        override fun onSession(state: AapClient.SessionState, detail: String?) {
            if (!isCurrentAapClient(generation, address)) return
            Log.i(TAG, "AAP session state=$state${detail?.let { ": $it" } ?: ""}")
            when (state) {
                AapClient.SessionState.CONNECTING ->
                    updateAapState(AapBus.SessionState.CONNECTING, detail)
                AapClient.SessionState.ACTIVE -> {
                    finishAapTransportAttempt()
                    aapSessionPolicy.onActive()
                    updateAapState(AapBus.SessionState.ACTIVE)
                }
                AapClient.SessionState.FAILED -> {
                    finishAapTransportAttempt()
                    aap = null
                    aapDeviceAddress = null
                    aapEarAuthoritative = false
                    aapBothPodsInCase = false
                    aapSessionPolicy.onFailure(SystemClock.elapsedRealtime())
                    AapBus.batteryLine = null
                    AapBus.ancMode = null
                    updateAapState(
                        AapBus.SessionState.RETRYING,
                        detail ?: "AAP channel closed; retrying",
                    )
                }
                AapClient.SessionState.UNSUPPORTED -> {
                    finishAapTransportAttempt()
                    aap = null
                    aapDeviceAddress = null
                    aapEarAuthoritative = false
                    aapBothPodsInCase = false
                    aapSessionPolicy.reset()
                    AapBus.batteryLine = null
                    AapBus.ancMode = null
                    if (connector.isConnected(address)) {
                        aapTransportUnsupported = true
                        updateAapState(
                            AapBus.SessionState.UNSUPPORTED,
                            detail
                                ?: "both Classic L2CAP socket modes timed out on this Android 11 build",
                        )
                    } else {
                        aapTransportUnsupported = false
                        updateAapState(AapBus.SessionState.WAITING_FOR_CONNECTION)
                    }
                }
                AapClient.SessionState.STOPPED -> {
                    finishAapTransportAttempt()
                    updateAapState(AapBus.SessionState.WAITING_FOR_CONNECTION)
                }
            }
            AapBus.notifyChanged()
        }

        override fun onBattery(batteries: Map<AapClient.Component, AapClient.Battery>) {
            if (!isCurrentAapClient(generation, address)) return
            val parts = listOf(
                AapClient.Component.LEFT to "L",
                AapClient.Component.RIGHT to "R",
                AapClient.Component.CASE to "Case",
            ).mapNotNull { (component, label) ->
                batteries[component]?.let {
                    "$label ${it.percent}%" + if (it.charging) "⚡" else ""
                }
            }
            if (parts.isEmpty()) return
            val batteryLine = parts.joinToString(" · ")
            AapBus.batteryLine = batteryLine
            AapBus.notifyChanged()
            overlay?.setSubtitle(batteryLine, 0xFF64D987.toInt())
        }

        override fun onEar(primary: AapClient.Placement, secondary: AapClient.Placement) {
            if (!isCurrentAapClient(generation, address)) return
            if (primary == AapClient.Placement.UNKNOWN ||
                secondary == AapClient.Placement.UNKNOWN
            ) {
                Log.w(TAG, "Ignoring incomplete AAP placement sample: $primary/$secondary")
                return
            }
            val now = SystemClock.elapsedRealtime()
            aapEarAuthoritative = true
            val inEarCount = listOf(primary, secondary).count { it == AapClient.Placement.IN_EAR }
            handleEarCount(inEarCount, requireStreak = false)
            val bothInCase = primary == AapClient.Placement.IN_CASE &&
                secondary == AapClient.Placement.IN_CASE
            aapBothPodsInCase = bothInCase
            if (bothInCase) {
                lidClosePolicy.onSignal(
                    bothPodsInCase = true,
                    explicitlyClosed = false,
                    nowMs = now,
                )
            } else {
                resetLidCloseCycle()
            }
        }

        override fun onAncMode(wireMode: Int) {
            if (!isCurrentAapClient(generation, address)) return
            AapBus.ancMode = wireMode
            AapBus.notifyChanged()
        }

        override fun onIrk(irk: ByteArray) {
            if (!isCurrentAapClient(generation, address)) return
            prefs.storeIdentityKey(address, RpaVerifier.hex(irk))
            refreshIrk()
            val verifiedNow = listOfNotNull(latestAcceptedBeacon, latestBeacon)
                .any { RpaVerifier.verify(it.address, irk) }
            if (verifiedNow) {
                identityKeyVerified = true
                prefs.irkVerified = true
                Log.i(TAG, "New identity key verified against the latest beacon")
            } else {
                Log.w(TAG, "Identity key captured but not verified against a live beacon yet")
            }
            AapBus.notifyChanged()
        }
    }

    private fun updateAapState(state: AapBus.SessionState, detail: String? = null) {
        val changed = AapBus.sessionState != state || AapBus.sessionDetail != detail
        AapBus.sessionState = state
        AapBus.sessionDetail = detail
        if (changed) AapBus.notifyChanged()
    }

    private fun disconnectSelectedDevice(
        reason: String,
        expectedAddress: String? = prefs.deviceAddress,
    ) {
        val address = expectedAddress ?: return
        if (prefs.deviceAddress != address) return
        if (disconnectInFlight || !connector.isConnected(address)) return
        val cycleGeneration = lidCloseCycleGeneration
        disconnectInFlight = true
        lidDisconnectAttempts++
        Log.i(TAG, "$reason; disconnecting $address")
        stopAap(AapBus.SessionState.WAITING_FOR_CONNECTION)
        connector.disconnectWhenReady(address) { result ->
            // A reset, new connection, or device change owns the state now; an old
            // callback must never disconnect or mutate that newer session.
            if (cycleGeneration == lidCloseCycleGeneration && prefs.deviceAddress == address) {
                prefs.serviceStatus = "$reason: ${result.message}"
                Log.i(TAG, "Lid-close disconnect result: ${result.message}")
                if (result is ProfileConnector.DisconnectResult.Success) {
                    resetConnectedReactions()
                } else if (prefs.autoDisconnectOnLidClose &&
                    connector.isConnected(address) &&
                    lidDisconnectAttempts < LID_DISCONNECT_MAX_ATTEMPTS
                ) {
                    Log.i(TAG, "Retrying lid-close disconnect after failure")
                    // Keep AAP reconciliation suspended while the retry is pending.
                    main.postDelayed({
                        if (cycleGeneration == lidCloseCycleGeneration &&
                            prefs.deviceAddress == address
                        ) {
                            disconnectInFlight = false
                            if (prefs.autoDisconnectOnLidClose && connector.isConnected(address)) {
                                disconnectSelectedDevice(reason, address)
                            }
                        }
                    }, LID_DISCONNECT_RETRY_MS)
                } else {
                    disconnectInFlight = false
                }
            }
        }
    }

    private fun resetLidCloseCycle() {
        lidCloseCycleGeneration++
        lidClosePolicy.reset()
        lidDisconnectAttempts = 0
        disconnectInFlight = false
        podsOutsideCaseStreak = 0
    }

    private fun resetConnectedReactions(keepConnectionFlag: Boolean = false) {
        earPausePolicy.reset()
        resetLidCloseCycle()
        aapEarAuthoritative = false
        aapBothPodsInCase = false
        earStreakValue = null
        earStreak = 0
        if (!keepConnectionFlag) {
            connectedSessionInitialized = false
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AirPods scanner", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AirPods Overlay")
            .setContentText("Watching for your AirPods case")
            .setContentIntent(pi)
            .build()
    }
}
