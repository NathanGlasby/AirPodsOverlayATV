package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePrerequisitesTest {
    @Test
    fun android13Through15RequireBluetoothAndLocationButNotNotifications() {
        for (sdk in 33..35) {
            val plan = ServicePrerequisites.forSdk(sdk)

            assertEquals(
                setOf(
                    ServiceCapability.BLUETOOTH_SCAN,
                    ServiceCapability.BLUETOOTH_CONNECT,
                    ServiceCapability.FINE_LOCATION,
                ),
                plan.required,
            )
            assertEquals(setOf(ServiceCapability.NOTIFICATIONS), plan.optional)
            assertEquals(setOf(ServiceCapability.COARSE_LOCATION), plan.requestCompanions)
            assertTrue(plan.locationServicesRequired)
        }
    }

    @Test
    fun android12And12LRequireLocationWithoutRequestingNotificationPermission() {
        for (sdk in 31..32) {
            val plan = ServicePrerequisites.forSdk(sdk)

            assertTrue(ServiceCapability.NOTIFICATIONS !in plan.requestable)
            assertTrue(ServiceCapability.FINE_LOCATION in plan.required)
            assertTrue(ServiceCapability.COARSE_LOCATION in plan.requestable)
            assertTrue(ServiceCapability.COARSE_LOCATION !in plan.required)
            assertTrue(plan.locationServicesRequired)
        }
    }

    @Test
    fun android10And11RequireBackgroundLocation() {
        for (sdk in 29..30) {
            val plan = ServicePrerequisites.forSdk(sdk)
            assertEquals(
                setOf(
                    ServiceCapability.FINE_LOCATION,
                    ServiceCapability.BACKGROUND_LOCATION,
                ),
                plan.required,
            )
            assertTrue(plan.locationServicesRequired)
        }
    }

    @Test
    fun android9NeedsOnlyForegroundLocation() {
        val plan = ServicePrerequisites.forSdk(28)

        assertEquals(setOf(ServiceCapability.FINE_LOCATION), plan.required)
        assertTrue(plan.locationServicesRequired)
    }

    @Test
    fun deniedOptionalNotificationDoesNotBlockStart() {
        val plan = ServicePrerequisites.forSdk(33)
        val readiness = ready(plan, granted = plan.required)

        assertTrue(readiness.canStart)
        assertTrue(readiness.missingRequired.isEmpty())
    }

    @Test
    fun eachRuntimePrerequisiteBlocksStart() {
        val plan = ServicePrerequisites.forSdk(33)

        assertFalse(ready(plan, granted = emptySet()).canStart)
        assertFalse(ready(plan, bluetoothAvailable = false).canStart)
        assertFalse(ready(plan, bluetoothEnabled = false).canStart)
        assertFalse(ready(plan, locationEnabled = false).canStart)
        assertFalse(ready(plan, overlayGranted = false).canStart)

        val legacy = ServicePrerequisites.forSdk(30)
        assertFalse(ready(legacy, granted = legacy.required, locationEnabled = false).canStart)
    }

    @Test
    fun policyRecoveryCanSkipOverlayRequirement() {
        val plan = ServicePrerequisites.forPolicyRecovery(33)
        assertEquals(setOf(ServiceCapability.BLUETOOTH_CONNECT), plan.required)
        assertFalse(ServiceCapability.BLUETOOTH_SCAN in plan.required)
        val readiness = ready(
            plan = plan,
            granted = plan.required,
            overlayGranted = false,
            requireOverlay = false,
        )

        assertTrue(readiness.canStart)
    }

    private fun ready(
        plan: ServicePermissionPlan,
        granted: Set<ServiceCapability> = plan.required,
        bluetoothAvailable: Boolean = true,
        bluetoothEnabled: Boolean = true,
        locationEnabled: Boolean = true,
        overlayGranted: Boolean = true,
        requireOverlay: Boolean = true,
    ) = ServicePrerequisites.evaluate(
        plan = plan,
        granted = granted,
        bluetoothAvailable = bluetoothAvailable,
        bluetoothEnabled = bluetoothEnabled,
        locationServicesEnabled = locationEnabled,
        overlayGranted = overlayGranted,
        requireOverlay = requireOverlay,
    )
}
