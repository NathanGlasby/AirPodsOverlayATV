package dev.nathan.airpodstv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanOwnershipPolicyTest {
    @Test
    fun scanCannotRestartDuringAapTransport() {
        assertFalse(
            ScanOwnershipPolicy.canStartScan(
                scannerActive = false,
                aapTransportInFlight = true,
                nowMs = 2_000L,
                resumeAtMs = 0L,
            )
        )
        assertFalse(
            ScanOwnershipPolicy.canStartScan(
                scannerActive = true,
                aapTransportInFlight = false,
                nowMs = 2_000L,
                resumeAtMs = 0L,
            )
        )
    }

    @Test
    fun scanWaitsForResumeDeadline() {
        assertFalse(
            ScanOwnershipPolicy.canStartScan(
                scannerActive = false,
                aapTransportInFlight = false,
                nowMs = 999L,
                resumeAtMs = 1_000L,
            )
        )
        assertTrue(
            ScanOwnershipPolicy.canStartScan(
                scannerActive = false,
                aapTransportInFlight = false,
                nowMs = 1_000L,
                resumeAtMs = 1_000L,
            )
        )
    }

    @Test
    fun silenceChecksRequireAnActiveResumedScanner() {
        assertFalse(
            ScanOwnershipPolicy.canRunSilenceChecks(
                scannerActive = false,
                aapTransportInFlight = false,
                nowMs = 2_000L,
                resumeAtMs = 1_000L,
            )
        )
        assertFalse(
            ScanOwnershipPolicy.canRunSilenceChecks(
                scannerActive = true,
                aapTransportInFlight = true,
                nowMs = 2_000L,
                resumeAtMs = 1_000L,
            )
        )
        assertFalse(
            ScanOwnershipPolicy.canRunSilenceChecks(
                scannerActive = true,
                aapTransportInFlight = false,
                nowMs = 999L,
                resumeAtMs = 1_000L,
            )
        )
        assertTrue(
            ScanOwnershipPolicy.canRunSilenceChecks(
                scannerActive = true,
                aapTransportInFlight = false,
                nowMs = 2_000L,
                resumeAtMs = 1_000L,
            )
        )
    }
}
