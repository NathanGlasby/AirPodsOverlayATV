package dev.nathan.airpodstv

/** Decides whether a BLE beacon may drive reactions for the connected AirPods. */
internal object ConnectedReactionPolicy {
    fun allowsBleReaction(
        selectedDeviceConnected: Boolean,
        identityKeyVerified: Boolean,
        identityMatched: Boolean,
    ): Boolean = selectedDeviceConnected && identityKeyVerified && identityMatched
}
