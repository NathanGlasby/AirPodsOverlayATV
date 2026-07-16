package dev.nathan.airpodstv

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log

internal object AndroidServicePrerequisites {
    private const val TAG = "ServicePrerequisites"

    data class Snapshot(
        val plan: ServicePermissionPlan,
        val granted: Set<ServiceCapability>,
        val readiness: ServiceReadiness,
    ) {
        val missingRequired: Set<ServiceCapability> get() = readiness.missingRequired
        val missingOptional: Set<ServiceCapability> get() = plan.optional - granted

        fun failureMessage(): String = when {
            ServiceBlocker.REQUIRED_PERMISSION in readiness.blockers ->
                "Required permission missing: " + missingRequired.joinToString { it.displayLabel }
            ServiceBlocker.BLUETOOTH_UNAVAILABLE in readiness.blockers ->
                "Bluetooth is unavailable on this device"
            ServiceBlocker.BLUETOOTH_DISABLED in readiness.blockers ->
                "Turn on Bluetooth before starting the scanner"
            ServiceBlocker.LOCATION_SERVICES_DISABLED in readiness.blockers ->
                "Turn on Android Location so Bluetooth beacons are visible"
            ServiceBlocker.OVERLAY_PERMISSION in readiness.blockers ->
                "Grant the overlay permission before starting the scanner"
            else -> "Scanner prerequisites are ready"
        }
    }

    fun capture(
        context: Context,
        requireOverlay: Boolean,
        plan: ServicePermissionPlan = ServicePrerequisites.forSdk(Build.VERSION.SDK_INT),
    ): Snapshot {
        val granted = plan.requestable.filterTo(linkedSetOf()) {
            context.checkSelfPermission(permissionName(it)) == PackageManager.PERMISSION_GRANTED
        }
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth service lookup failed", e)
            null
        }
        val bluetoothEnabled = try {
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth state permission was denied", e)
            false
        }
        val locationEnabled = if (!plan.locationServicesRequired) {
            true
        } else {
            try {
                (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                    .isLocationEnabled
            } catch (e: Exception) {
                Log.w(TAG, "Location service lookup failed", e)
                false
            }
        }
        val overlayGranted = !requireOverlay || Settings.canDrawOverlays(context)
        return Snapshot(
            plan = plan,
            granted = granted,
            readiness = ServicePrerequisites.evaluate(
                plan = plan,
                granted = granted,
                bluetoothAvailable = adapter != null,
                bluetoothEnabled = bluetoothEnabled,
                locationServicesEnabled = locationEnabled,
                overlayGranted = overlayGranted,
                requireOverlay = requireOverlay,
            ),
        )
    }

    fun permissionName(capability: ServiceCapability): String = when (capability) {
        ServiceCapability.BLUETOOTH_SCAN -> Manifest.permission.BLUETOOTH_SCAN
        ServiceCapability.BLUETOOTH_CONNECT -> Manifest.permission.BLUETOOTH_CONNECT
        ServiceCapability.FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        ServiceCapability.COARSE_LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
        ServiceCapability.BACKGROUND_LOCATION -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ServiceCapability.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
    }

    fun capabilityLabel(capability: ServiceCapability): String = capability.displayLabel

}

private val ServiceCapability.displayLabel: String
    get() = when (this) {
        ServiceCapability.BLUETOOTH_SCAN -> "Bluetooth scan"
        ServiceCapability.BLUETOOTH_CONNECT -> "Bluetooth connect"
        ServiceCapability.FINE_LOCATION -> "location"
        ServiceCapability.COARSE_LOCATION -> "approximate location"
        ServiceCapability.BACKGROUND_LOCATION -> "background location"
        ServiceCapability.NOTIFICATIONS -> "notifications"
    }
