package dev.nathan.airpodstv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission", "UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity(), BeaconBus.Listener, AapBus.Listener {

    private companion object {
        const val REQUEST_FOREGROUND_PERMISSIONS = 1
        const val REQUEST_BACKGROUND_LOCATION = 2
    }

    private lateinit var prefs: Prefs

    private lateinit var permStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var grantPermsBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var deviceList: LinearLayout
    private lateinit var enabledSwitch: Switch
    private lateinit var autoConnectSwitch: Switch
    private lateinit var blockAutoSwitch: Switch
    private lateinit var rssiBtn: Button
    private lateinit var renameBtn: Button
    private lateinit var modelFilterSwitch: Switch
    private lateinit var identitySwitch: Switch
    private lateinit var autoPauseSwitch: Switch
    private lateinit var autoDisconnectSwitch: Switch
    private lateinit var aapSwitch: Switch
    private lateinit var aapStatus: TextView
    private lateinit var ancButtons: Map<Int, Button>
    private lateinit var testBtn: Button
    private lateinit var debugText: TextView
    private lateinit var policyStatus: TextView
    private lateinit var restorePolicyBtn: Button

    private val debugLines = ArrayDeque<String>()
    private var refreshingUi = false

    private val rssiPresets = listOf(
        -45 to "Near (~1 m)",
        -60 to "Medium (~3 m)",
        -75 to "Far (~6 m)",
        -100 to "Any distance",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        permStatus = findViewById(R.id.permStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        grantPermsBtn = findViewById(R.id.grantPermsBtn)
        overlayBtn = findViewById(R.id.overlayBtn)
        deviceList = findViewById(R.id.deviceList)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        autoConnectSwitch = findViewById(R.id.autoConnectSwitch)
        blockAutoSwitch = findViewById(R.id.blockAutoSwitch)
        rssiBtn = findViewById(R.id.rssiBtn)
        renameBtn = findViewById(R.id.renameBtn)
        renameBtn.setOnClickListener { showRenameDialog() }

        modelFilterSwitch = findViewById(R.id.modelFilterSwitch)
        identitySwitch = findViewById(R.id.identitySwitch)
        autoPauseSwitch = findViewById(R.id.autoPauseSwitch)
        autoDisconnectSwitch = findViewById(R.id.autoDisconnectSwitch)
        aapSwitch = findViewById(R.id.aapSwitch)
        aapStatus = findViewById(R.id.aapStatus)

        modelFilterSwitch.setOnCheckedChangeListener { _, c -> if (!refreshingUi) prefs.modelFilter = c }
        identitySwitch.setOnCheckedChangeListener { _, c ->
            if (refreshingUi) return@setOnCheckedChangeListener
            prefs.identityFilter = c
            if (c && !prefs.irkVerified) {
                toast("Identity protection will use distance until the captured key is verified")
            }
        }
        autoPauseSwitch.setOnCheckedChangeListener { _, c -> if (!refreshingUi) prefs.autoPause = c }
        autoDisconnectSwitch.setOnCheckedChangeListener { _, c -> if (!refreshingUi) prefs.autoDisconnectOnLidClose = c }
        aapSwitch.setOnCheckedChangeListener { _, c -> if (!refreshingUi) prefs.aapEnabled = c }

        ancButtons = mapOf(
            AapClient.ANC_OFF to findViewById<Button>(R.id.ancOffBtn),
            AapClient.ANC_ON to findViewById<Button>(R.id.ancOnBtn),
            AapClient.ANC_TRANSPARENCY to findViewById<Button>(R.id.ancTransBtn),
            AapClient.ANC_ADAPTIVE to findViewById<Button>(R.id.ancAdaptiveBtn),
        )
        ancButtons.forEach { (wireMode, button) ->
            button.setOnClickListener {
                val service = BleScanService.instance
                if (service == null || !AapBus.sessionActive) {
                    toast("Connect your AirPods first. ANC control needs a live session")
                } else {
                    service.sendAncMode(wireMode)
                }
            }
        }
        testBtn = findViewById(R.id.testBtn)
        debugText = findViewById(R.id.debugText)
        policyStatus = findViewById(R.id.policyStatus)
        restorePolicyBtn = findViewById(R.id.restorePolicyBtn)
        restorePolicyBtn.setOnClickListener {
            prefs.blockAutoConnect = false
            refreshingUi = true
            try {
                blockAutoSwitch.isChecked = false
            } finally {
                refreshingUi = false
            }
            if (BleScanService.start(this, BleScanService.ACTION_RESTORE_POLICY)) {
                toast("Restoring the TV's normal auto-connect policy")
            } else {
                toast("The recovery service could not start; reopen the app and try again")
            }
            refreshUi()
        }

        findViewById<Button>(R.id.licensesBtn).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }

        grantPermsBtn.setOnClickListener { requestNeededPermissions() }
        overlayBtn.setOnClickListener { openOverlaySettings() }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (refreshingUi) return@setOnCheckedChangeListener
            if (checked) {
                if (readyToRun()) {
                    prefs.enabled = true
                    BleScanService.start(this)
                } else {
                    enabledSwitch.isChecked = false
                }
            } else {
                prefs.enabled = false
                BleScanService.stop(this)
            }
        }
        autoConnectSwitch.setOnCheckedChangeListener { _, checked ->
            if (!refreshingUi) prefs.autoConnect = checked
        }
        blockAutoSwitch.setOnCheckedChangeListener { _, checked ->
            if (refreshingUi) return@setOnCheckedChangeListener
            prefs.blockAutoConnect = checked
            if (BleScanService.isRunning) {
                BleScanService.start(this, BleScanService.ACTION_APPLY_POLICY)
            }
        }
        rssiBtn.setOnClickListener {
            val idx = rssiPresets.indexOfFirst { it.first == prefs.rssiThreshold }
            val next = rssiPresets[(idx + 1).mod(rssiPresets.size)]
            prefs.rssiThreshold = next.first
            updateRssiLabel()
        }
        testBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant the overlay permission first")
                return@setOnClickListener
            }
            if (!BleScanService.isRunning) {
                toast("Enable the scanner first")
                return@setOnClickListener
            }
            BleScanService.start(this, BleScanService.ACTION_TEST_POPUP)
            toast("Test popup in 3 seconds. Switch to another app to see it overlay")
        }
    }

    override fun onResume() {
        super.onResume()
        BeaconBus.listener = this
        AapBus.listener = this
        refreshUi()
        onAapChanged()
    }

    override fun onPause() {
        super.onPause()
        BeaconBus.listener = null
        AapBus.listener = null
    }

    override fun onAapChanged() {
        val irkCaptured = prefs.irkHex != null
        val session = if (AapBus.sessionActive) "session active" else "no session (connect to start)"
        val identity = when {
            !irkCaptured -> "identity key not captured yet; connect once"
            prefs.irkVerified -> "identity key verified ✓"
            else -> "identity key captured; awaiting live verification (distance fallback active)"
        }
        val batteryPart = AapBus.batteryLine?.let { "\nBattery: $it" } ?: ""
        aapStatus.text = "Status: $session · $identity$batteryPart"
        identitySwitch.isEnabled = irkCaptured

        ancButtons.forEach { (wireMode, button) ->
            button.isEnabled = AapBus.sessionActive
            button.setTextColor(
                if (AapBus.ancMode == wireMode) 0xFF4C8DFF.toInt() else 0xFFF2F2F5.toInt()
            )
        }
    }

    // ---- permissions ----

    private fun neededPermissions(): List<String> {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= 29) {
                need += Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        return need
    }

    private fun missingPermissions(): List<String> =
        neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    private fun locationServicesReady(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return true
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        return manager.isLocationEnabled
    }

    private fun requestNeededPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            if (!locationServicesReady()) {
                toast("Turn on Android Location so Bluetooth beacons are visible")
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                    toast("Open Android Settings and turn on Location")
                }
                return
            }
            toast("All permissions already granted")
            return
        }

        // Android ignores a background-location request made together with foreground location.
        val foregroundMissing = missing.filterNot {
            it == Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (foregroundMissing.isNotEmpty()) {
            requestPermissions(foregroundMissing.toTypedArray(), REQUEST_FOREGROUND_PERMISSIONS)
            return
        }

        if (Manifest.permission.ACCESS_BACKGROUND_LOCATION in missing) {
            if (Build.VERSION.SDK_INT >= 30) {
                toast("In Permissions > Location, choose Allow all the time so scanning works over other apps")
                openAppSettings()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_BACKGROUND_LOCATION,
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
        if (requestCode == REQUEST_FOREGROUND_PERMISSIONS && Build.VERSION.SDK_INT in 29..30 &&
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            // Continue the required two-step flow on Android 10/11.
            requestNeededPermissions()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            toast("Open Android Settings > Apps > AirPods Overlay > Permissions > Location")
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                toast("No settings UI. Run: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
            }
        }
    }

    private fun readyToRun(): Boolean {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            val message = if (Manifest.permission.ACCESS_BACKGROUND_LOCATION in missing) {
                "Allow location all the time so the scanner works over other apps"
            } else {
                "Grant Bluetooth/location permissions first"
            }
            toast(message)
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant the overlay permission first")
            return false
        }
        if (!locationServicesReady()) {
            toast("Turn on Android Location so the scanner can receive BLE beacons")
            return false
        }
        return true
    }

    // ---- UI ----

    private fun refreshUi() {
        refreshingUi = true
        val missing = missingPermissions()
        val locationOk = locationServicesReady()
        permStatus.text = when {
            missing.isNotEmpty() -> "✗ Missing: " + missing.joinToString(", ") { it.substringAfterLast('.') }
            !locationOk -> "✗ Android Location is off (required for BLE scanning on Android 11 and older)"
            else -> "✓ Bluetooth & background scan permissions granted"
        }
        grantPermsBtn.visibility = if (missing.isEmpty() && locationOk) View.GONE else View.VISIBLE

        val overlayOk = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlayOk) "✓ Overlay permission granted"
        else "✗ Overlay permission needed (Display over other apps)"
        overlayBtn.visibility = if (overlayOk) View.GONE else View.VISIBLE

        enabledSwitch.isChecked = prefs.enabled && BleScanService.isRunning
        autoConnectSwitch.isChecked = prefs.autoConnect
        blockAutoSwitch.isChecked = prefs.blockAutoConnect
        modelFilterSwitch.isChecked = prefs.modelFilter
        identitySwitch.isChecked = prefs.identityFilter
        identitySwitch.isEnabled = prefs.irkHex != null
        autoPauseSwitch.isChecked = prefs.autoPause
        autoDisconnectSwitch.isChecked = prefs.autoDisconnectOnLidClose
        aapSwitch.isChecked = prefs.aapEnabled
        policyStatus.text = when {
            prefs.connectionPolicyBlocked -> "Recovery available  •  TV auto-connect is currently blocked"
            prefs.blockAutoConnect -> "Active  •  This app will block the TV's automatic connection"
            else -> "Ready  •  The TV can use its normal auto-connect behavior"
        }
        restorePolicyBtn.visibility = if (prefs.connectionPolicyBlocked || prefs.blockAutoConnect) {
            View.VISIBLE
        } else {
            View.GONE
        }
        prefs.serviceStatus?.let { status ->
            if (status.isNotBlank()) policyStatus.append("\nLast service state: $status")
        }
        updateRssiLabel()
        updateRenameLabel()
        populateDevices()
        refreshingUi = false
    }

    private fun updateRenameLabel() {
        val custom = prefs.customName
        renameBtn.text = if (custom != null) "Popup name: $custom"
        else "Popup name: (device name)"
    }

    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(prefs.customName ?: "")
            hint = "Nate's AirPods"
            isSingleLine = true
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Popup name")
            .setMessage("Shown in the connect popup instead of the Bluetooth device name.")
            .setView(wrapper)
            .setPositiveButton("Save") { _, _ ->
                prefs.customName = input.text.toString()
                updateRenameLabel()
            }
            .setNeutralButton("Use device name") { _, _ ->
                prefs.customName = null
                updateRenameLabel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateRssiLabel() {
        val label = rssiPresets.firstOrNull { it.first == prefs.rssiThreshold }?.second
            ?: "${prefs.rssiThreshold} dBm"
        rssiBtn.text = "Trigger distance: $label"
    }

    private fun populateDevices() {
        deviceList.removeAllViews()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bonded = try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (bonded.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = "No paired Bluetooth devices found.\nPair your AirPods in the TV's Bluetooth settings first."
                setTextColor(0xFFA8A8B0.toInt())
                textSize = 14f
            })
            return
        }
        // AirPods-looking devices first.
        val sorted = bonded.sortedByDescending {
            val n = (it.name ?: "").lowercase()
            "airpod" in n || "pods" in n || "beats" in n
        }
        for (dev in sorted) {
            val selected = dev.address == prefs.deviceAddress
            val row = TextView(this).apply {
                text = (if (selected) "●  " else "○  ") + (dev.name ?: dev.address)
                textSize = 16f
                setTextColor(if (selected) 0xFF4C8DFF.toInt() else 0xFFF2F2F5.toInt())
                setPadding(24, 20, 24, 20)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.focus_bg)
                setOnClickListener {
                    val oldAddress = prefs.deviceAddress
                    val deviceChanged = oldAddress != dev.address
                    prefs.deviceAddress = dev.address
                    prefs.deviceName = dev.name ?: "AirPods"
                    if (deviceChanged) {
                        // An IRK belongs to one physical device; never carry it to a new selection.
                        prefs.irkHex = null
                        if (BleScanService.isRunning) {
                            BleScanService.deviceChanged(this@MainActivity, oldAddress)
                        }
                        onAapChanged()
                    }
                    populateDevices()
                }
            }
            deviceList.addView(row)
        }
    }

    // ---- live beacon debug ----

    private val rawFrames = LinkedHashMap<Int, String>()

    override fun onBeacon(
        beacon: BeaconParser.Beacon,
        passedFilter: Boolean,
        filterReason: String,
    ) {
        val mark = if (passedFilter) "●" else "○"
        val line = "$mark ${beacon.modelName}  ${beacon.rssi} dBm  lid=${beacon.lidState}" +
            "  st=0x%02X lb=0x%02X".format(beacon.statusByte, beacon.lidByte) +
            "  L=${beacon.leftBattery ?: "-"} R=${beacon.rightBattery ?: "-"} C=${beacon.caseBattery ?: "-"}" +
            "  [$filterReason]"
        if (debugLines.firstOrNull() != line) {
            debugLines.addFirst(line)
            while (debugLines.size > 6) debugLines.removeLast()
            renderDebug()
        }
    }

    override fun onRawFrame(type: Int, hex: String, rssi: Int) {
        val line = "type=0x%02X  %d dBm  %s".format(type, rssi, hex)
        if (rawFrames[type] != line) {
            rawFrames[type] = line
            while (rawFrames.size > 8) rawFrames.remove(rawFrames.keys.first())
            renderDebug()
        }
    }

    private fun renderDebug() {
        val sb = StringBuilder()
        if (debugLines.isEmpty()) sb.append("(no AirPods beacons yet)")
        else sb.append(debugLines.joinToString("\n"))
        if (rawFrames.isNotEmpty()) {
            sb.append("\nOther Apple frames\n")
            sb.append(rawFrames.values.joinToString("\n"))
        }
        debugText.text = sb.toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
