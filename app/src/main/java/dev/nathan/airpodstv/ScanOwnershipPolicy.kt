package dev.nathan.airpodstv

internal object ScanOwnershipPolicy {
    fun canStartScan(
        scannerActive: Boolean,
        aapTransportInFlight: Boolean,
        nowMs: Long,
        resumeAtMs: Long,
    ): Boolean = !scannerActive && !aapTransportInFlight && nowMs >= resumeAtMs

    fun canRunSilenceChecks(
        scannerActive: Boolean,
        aapTransportInFlight: Boolean,
        nowMs: Long,
        resumeAtMs: Long,
    ): Boolean = scannerActive && !aapTransportInFlight && nowMs >= resumeAtMs
}
