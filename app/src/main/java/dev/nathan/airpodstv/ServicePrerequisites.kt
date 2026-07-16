package dev.nathan.airpodstv

internal enum class ServiceCapability {
    BLUETOOTH_SCAN,
    BLUETOOTH_CONNECT,
    FINE_LOCATION,
    COARSE_LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
}

internal data class ServicePermissionPlan(
    val required: Set<ServiceCapability>,
    val optional: Set<ServiceCapability>,
    val locationServicesRequired: Boolean,
    /** Permissions Android requires in the same dialog, but which do not prove readiness. */
    val requestCompanions: Set<ServiceCapability> = emptySet(),
) {
    val requestable: Set<ServiceCapability> get() = required + optional + requestCompanions
}

internal enum class ServiceBlocker {
    REQUIRED_PERMISSION,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    LOCATION_SERVICES_DISABLED,
    OVERLAY_PERMISSION,
}

internal data class ServiceReadiness(
    val blockers: Set<ServiceBlocker>,
    val missingRequired: Set<ServiceCapability>,
) {
    val canStart: Boolean get() = blockers.isEmpty()
}

/** Pure permission and hardware policy shared by setup, boot, and the foreground service. */
internal object ServicePrerequisites {
    fun forSdk(sdk: Int): ServicePermissionPlan = when {
        sdk >= 33 -> ServicePermissionPlan(
            required = setOf(
                ServiceCapability.BLUETOOTH_SCAN,
                ServiceCapability.BLUETOOTH_CONNECT,
                ServiceCapability.FINE_LOCATION,
            ),
            optional = setOf(ServiceCapability.NOTIFICATIONS),
            locationServicesRequired = true,
            requestCompanions = setOf(ServiceCapability.COARSE_LOCATION),
        )
        sdk >= 31 -> ServicePermissionPlan(
            required = setOf(
                ServiceCapability.BLUETOOTH_SCAN,
                ServiceCapability.BLUETOOTH_CONNECT,
                ServiceCapability.FINE_LOCATION,
            ),
            optional = emptySet(),
            locationServicesRequired = true,
            requestCompanions = setOf(ServiceCapability.COARSE_LOCATION),
        )
        sdk >= 29 -> ServicePermissionPlan(
            required = setOf(
                ServiceCapability.FINE_LOCATION,
                ServiceCapability.BACKGROUND_LOCATION,
            ),
            optional = emptySet(),
            locationServicesRequired = true,
        )
        else -> ServicePermissionPlan(
            required = setOf(ServiceCapability.FINE_LOCATION),
            optional = emptySet(),
            locationServicesRequired = true,
        )
    }

    fun forPolicyRecovery(sdk: Int): ServicePermissionPlan = ServicePermissionPlan(
        required = if (sdk >= 31) {
            setOf(ServiceCapability.BLUETOOTH_CONNECT)
        } else {
            emptySet()
        },
        optional = if (sdk >= 33) setOf(ServiceCapability.NOTIFICATIONS) else emptySet(),
        locationServicesRequired = false,
    )

    fun evaluate(
        plan: ServicePermissionPlan,
        granted: Set<ServiceCapability>,
        bluetoothAvailable: Boolean,
        bluetoothEnabled: Boolean,
        locationServicesEnabled: Boolean,
        overlayGranted: Boolean,
        requireOverlay: Boolean,
    ): ServiceReadiness {
        val missingRequired = plan.required - granted
        val blockers = linkedSetOf<ServiceBlocker>()
        if (missingRequired.isNotEmpty()) blockers += ServiceBlocker.REQUIRED_PERMISSION
        if (!bluetoothAvailable) {
            blockers += ServiceBlocker.BLUETOOTH_UNAVAILABLE
        } else if (!bluetoothEnabled) {
            blockers += ServiceBlocker.BLUETOOTH_DISABLED
        }
        if (plan.locationServicesRequired && !locationServicesEnabled) {
            blockers += ServiceBlocker.LOCATION_SERVICES_DISABLED
        }
        if (requireOverlay && !overlayGranted) blockers += ServiceBlocker.OVERLAY_PERMISSION
        return ServiceReadiness(blockers, missingRequired)
    }
}
