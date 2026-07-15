package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Test

class AapTransportPlanTest {

    @Test
    fun android11TriesBondedSecureTransportFirst() {
        assertEquals(
            listOf(
                AapTransportPlan.Security.SECURE,
                AapTransportPlan.Security.INSECURE,
            ),
            AapTransportPlan.forSdk(30),
        )
    }

    @Test
    fun newerAndroidKeepsReferenceInsecureTransportFirst() {
        assertEquals(
            listOf(
                AapTransportPlan.Security.INSECURE,
                AapTransportPlan.Security.SECURE,
            ),
            AapTransportPlan.forSdk(34),
        )
    }

    @Test
    fun everyAttemptHasABoundedRadioBlackout() {
        assertEquals(
            8_000L,
            AapTransportPlan.timeoutMs(30, AapTransportPlan.Security.SECURE),
        )
        assertEquals(
            8_000L,
            AapTransportPlan.timeoutMs(30, AapTransportPlan.Security.INSECURE),
        )
        assertEquals(
            8_000L,
            AapTransportPlan.timeoutMs(34, AapTransportPlan.Security.INSECURE),
        )
    }

    @Test
    fun failureDetailRetainsEveryAttempt() {
        assertEquals(
            "secure L2CAP timed out; insecure L2CAP rejected",
            AapTransportPlan.failureDetail(
                listOf("secure L2CAP timed out", "insecure L2CAP rejected")
            ),
        )
    }
}
