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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        /** Connections within this window of a popup Connect are considered user-initiated. */
        private const val USER_CONNECT_WINDOW_MS = 30_000L

        /** How long without a strong beacon before we assume the case closed. */
        private const val BEACON_GONE_MS = 5_000L

        /** After dismiss/connect, require beacons to vanish this long before a new popup. */
        private const val SESSION_RESET_MS = 8_000L

        var isRunning = false
            private set

        var instance: BleScanService? = null
            private set

        /** AirPods 4 (ANC) model id — the model filter reacts only to this. */
        private const val MODEL_AIRPODS_4_ANC = 0x1B20

        fun start(context: Context, action: String = ACTION_START) {
            val i = Intent(context, BleScanService::class.java).setAction(action)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleScanService::class.java))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var connector: ProfileConnector
    private var overlay: OverlayController? = null
    private var scanner: BluetoothLeScanner? = null
    private val main = Handler(Looper.getMainLooper())

    private var lastStrongBeaconAt = 0L
    private var lastLidClosedAt = 0L
    private var suppressed = false
    private var popupIsTest = false
    private var latestBeacon: BeaconParser.Beacon? = null
    private var connectRequestedAt = 0L
    private var irkBytes: ByteArray? = null
    private var aap: AapClient? = null
    private var aapEarActive = false
    private var lastBothInEar: Boolean? = null
    private var earStreakValue: Boolean? = null
    private var earStreak = 0

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address == null || device.address != prefs.deviceAddress) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val userInitiated =
                        System.currentTimeMillis() - connectRequestedAt < USER_CONNECT_WINDOW_MS
                    if (prefs.blockAutoConnect && !userInitiated) {
                        Log.i(TAG, "OS auto-connected ${device.address} — blocking")
                        // Forbidding the policy also makes the OS drop the link.
                        connector.setAutoConnectAllowed(prefs.deviceAddress, false)
                        connector.disconnect(prefs.deviceAddress)
                    } else {
                        onDeviceConnected()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    stopAap()
                    lastBothInEar = null
                    // Re-arm the block once the user-approved session ends.
                    if (prefs.blockAutoConnect) {
                        main.postDelayed({
                            connector.setAutoConnectAllowed(prefs.deviceAddress, false)
                        }, 1500L)
                    }
                }
            }
        }
    }

    /** FORBIDDEN would kick an active session, so only apply the block while disconnected. */
    private fun applyConnectionPolicy() {
        val addr = prefs.deviceAddress ?: return
        if (prefs.blockAutoConnect) {
            if (!connector.isConnected(addr)) connector.setAutoConnectAllowed(addr, false)
        } else {
            connector.setAutoConnectAllowed(addr, true)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (overlay?.isShowing == true && !popupIsTest && now - lastStrongBeaconAt > BEACON_GONE_MS) {
                Log.i(TAG, "Beacons stopped — dismissing popup")
                dismissPopup()
            }
            if (suppressed && now - lastStrongBeaconAt > SESSION_RESET_MS) {
                Log.i(TAG, "Session reset — popup re-armed")
                suppressed = false
            }
            // Long silence: next case-open is a fresh session.
            if (lastStrongBeaconAt != 0L && now - lastStrongBeaconAt > 60_000L) {
                lastStrongBeaconAt = 0L
            }
            main.postDelayed(this, 1000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val beacon = BeaconParser.parse(result)
            if (beacon != null) {
                if (prefs.modelFilter && beacon.model != MODEL_AIRPODS_4_ANC) {
                    BeaconBus.publish(beacon, false)
                    return
                }
                // Identity beats signal strength: with a captured IRK we can prove
                // a rotating address is OUR AirPods, at any distance.
                val irk = irkBytes
                val passes = if (irk != null && prefs.identityFilter) {
                    RpaVerifier.verify(beacon.address, irk)
                } else {
                    beacon.rssi >= prefs.rssiThreshold
                }
                BeaconBus.publish(beacon, passes)
                if (passes) handleBeacon(beacon)
                return
            }
            // Not a proximity-pairing frame — surface other Apple frame types so we
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
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        prefs = Prefs(this)
        refreshIrk()
        connector = ProfileConnector(this)
        connector.open()
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
        }
        refreshIrk()
        startScan()
        return START_STICKY
    }

    private fun refreshIrk() {
        irkBytes = prefs.irkHex?.let {
            try {
                RpaVerifier.fromHex(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        stopAap()
        main.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(aclReceiver)
        } catch (_: Exception) {
        }
        stopScan()
        dismissPopup()
        // With the app inactive there is no popup to connect through — restore
        // stock auto-connect behavior rather than leaving the AirPods blocked.
        if (prefs.blockAutoConnect) {
            connector.setAutoConnectAllowed(prefs.deviceAddress, true)
        }
        connector.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startScan() {
        if (scanner != null) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth off — cannot scan")
            return
        }
        scanner = adapter.bluetoothLeScanner
        // Match ANY Apple manufacturer frame; we sort out message types in the callback.
        val filter = ScanFilter.Builder()
            .setManufacturerData(BeaconParser.APPLE_COMPANY_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            scanner = null
        }
    }

    private fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanner = null
    }

    private fun handleBeacon(beacon: BeaconParser.Beacon) {
        val now = System.currentTimeMillis()
        lastStrongBeaconAt = now
        latestBeacon = beacon

        // In-ear transitions (only meaningful from a pod that's out of the case);
        // AAP ear events take over when a session is live.
        if (!beacon.podsInCase && !aapEarActive) {
            handleEarState(beacon.leftInEar && beacon.rightInEar, requireStreak = true)
        }

        when (beacon.lidState) {
            BeaconParser.LidState.CLOSED -> {
                lastLidClosedAt = now
                if (overlay?.isShowing == true) {
                    Log.i(TAG, "Lid closed — dismissing popup")
                    dismissPopup()
                    suppressed = true
                }
                if (prefs.autoDisconnectOnLidClose && connector.isConnected(prefs.deviceAddress)) {
                    Log.i(TAG, "Lid closed with pods in case — disconnecting")
                    connector.disconnect(prefs.deviceAddress)
                }
            }
            BeaconParser.LidState.OPEN -> {
                if (overlay?.isShowing != true && !suppressed) {
                    if (connector.isConnected(prefs.deviceAddress)) {
                        // Already connected; nothing to offer.
                        suppressed = true
                        return
                    }
                    showPopup()
                }
            }
            BeaconParser.LidState.UNKNOWN -> {
            }
        }
    }

    private fun showPopup(testMode: Boolean = false) {
        if (overlay?.isShowing == true) return
        popupIsTest = testMode
        val name = prefs.displayName ?: latestBeacon?.modelName ?: "AirPods"
        val ov = OverlayController(
            this,
            onConnect = {
                connectRequestedAt = System.currentTimeMillis()
                connector.connect(prefs.deviceAddress) { ok ->
                    overlay?.setResult(ok)
                    if (ok) suppressed = true
                }
            },
            onDismiss = {
                dismissPopup()
                suppressed = true
            },
        )
        overlay = ov
        ov.show(name, prefs.popupTimeoutSec)
        if (!testMode && prefs.autoConnect) {
            ov.setConnecting()
            connectRequestedAt = System.currentTimeMillis()
            connector.connect(prefs.deviceAddress) { ok ->
                overlay?.setResult(ok)
                if (ok) suppressed = true
            }
        }
    }

    private fun dismissPopup() {
        overlay?.hide()
        overlay = null
    }

    // ---- reactions ----

    /**
     * Pause playback when a pod leaves the ear, resume when both are back in.
     * BLE-sourced states require two consecutive identical frames (glitch guard);
     * AAP-sourced states are trusted directly.
     */
    private fun handleEarState(bothInEar: Boolean, requireStreak: Boolean) {
        if (!prefs.autoPause) {
            lastBothInEar = null
            return
        }
        if (!connector.isConnected(prefs.deviceAddress)) {
            lastBothInEar = null
            return
        }
        if (requireStreak) {
            if (bothInEar == earStreakValue) earStreak++ else {
                earStreakValue = bothInEar
                earStreak = 1
            }
            if (earStreak < 2) return
        }
        val prev = lastBothInEar
        lastBothInEar = bothInEar
        if (prev == null || prev == bothInEar) return
        val key = if (bothInEar) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        Log.i(TAG, if (bothInEar) "Both pods in ear — play" else "Pod removed — pause")
    }

    private fun onDeviceConnected() {
        lastBothInEar = null
        earStreak = 0
        if (prefs.aapEnabled) main.postDelayed({ startAap() }, 1500L)
        if (overlay?.isShowing != true && Settings.canDrawOverlays(this)) {
            val ov = OverlayController(this, onConnect = {}, onDismiss = { dismissPopup() })
            overlay = ov
            ov.show(prefs.displayName ?: "AirPods", 4, withButtons = false)
            ov.setSubtitle("Connected ✓", 0xFF3ACB74.toInt())
        }
    }

    // ---- AAP session ----

    fun sendAncMode(wireMode: Int) {
        aap?.setAncMode(wireMode)
    }

    private fun startAap() {
        if (aap?.isActive == true) return
        if (!connector.isConnected(prefs.deviceAddress)) return
        val device = connector.bondedDevice(prefs.deviceAddress) ?: return
        Log.i(TAG, "Starting AAP session")
        aap = AapClient(device, aapListener).also { it.start() }
    }

    private fun stopAap() {
        aap?.stop()
        aap = null
        aapEarActive = false
        AapBus.sessionActive = false
        AapBus.batteryLine = null
        AapBus.ancMode = null
        AapBus.notifyChanged()
    }

    private val aapListener = object : AapClient.Listener {
        override fun onSession(active: Boolean) {
            Log.i(TAG, "AAP session active=$active")
            AapBus.sessionActive = active
            if (!active) {
                aapEarActive = false
                AapBus.batteryLine = null
                AapBus.ancMode = null
            }
            AapBus.notifyChanged()
        }

        override fun onBattery(batteries: Map<AapClient.Component, AapClient.Battery>) {
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
            AapBus.batteryLine = parts.joinToString(" · ")
            AapBus.notifyChanged()
            overlay?.setSubtitle("✓ ${AapBus.batteryLine}", 0xFF3ACB74.toInt())
        }

        override fun onEar(primaryInEar: Boolean, secondaryInEar: Boolean, anyInCase: Boolean) {
            aapEarActive = true
            if (!anyInCase) handleEarState(primaryInEar && secondaryInEar, requireStreak = false)
        }

        override fun onAncMode(wireMode: Int) {
            AapBus.ancMode = wireMode
            AapBus.notifyChanged()
        }

        override fun onIrk(irk: ByteArray) {
            prefs.irkHex = RpaVerifier.hex(irk)
            refreshIrk()
            AapBus.notifyChanged()
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
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AirPods Overlay")
            .setContentText("Watching for your AirPods case")
            .setContentIntent(pi)
            .build()
    }
}
