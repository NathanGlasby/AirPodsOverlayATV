package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Test

class BleScanModePolicyTest {
    @Test
    fun idleDiscoveryUsesBalancedScanning() {
        assertEquals(
            BleScanModePolicy.Mode.BALANCED,
            BleScanModePolicy.desiredMode(
                selectedDeviceConnected = false,
                popupVisible = false,
            ),
        )
    }

    @Test
    fun connectedReactionsAndVisiblePopupUseLowLatencyScanning() {
        assertEquals(
            BleScanModePolicy.Mode.LOW_LATENCY,
            BleScanModePolicy.desiredMode(
                selectedDeviceConnected = true,
                popupVisible = false,
            ),
        )
        assertEquals(
            BleScanModePolicy.Mode.LOW_LATENCY,
            BleScanModePolicy.desiredMode(
                selectedDeviceConnected = false,
                popupVisible = true,
            ),
        )
    }
}
