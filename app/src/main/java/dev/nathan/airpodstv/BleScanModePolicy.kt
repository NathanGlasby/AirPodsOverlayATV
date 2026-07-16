package dev.nathan.airpodstv

/** Uses lower-power discovery while idle and responsive scanning during active interactions. */
internal object BleScanModePolicy {
    enum class Mode { BALANCED, LOW_LATENCY }

    fun desiredMode(
        selectedDeviceConnected: Boolean,
        popupVisible: Boolean,
    ): Mode = if (selectedDeviceConnected || popupVisible) {
        Mode.LOW_LATENCY
    } else {
        Mode.BALANCED
    }
}
