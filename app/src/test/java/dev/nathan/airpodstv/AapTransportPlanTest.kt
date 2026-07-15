package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun hiddenApiFailureDistinguishesAccessDenialFromTargetPermissionFailure() {
        assertTrue(AapTransportPlan.isHiddenApiAccessFailure(SecurityException("hidden API denied")))
        assertFalse(
            AapTransportPlan.isHiddenApiAccessFailure(
                java.lang.reflect.InvocationTargetException(
                    SecurityException("Bluetooth permission denied")
                )
            )
        )
    }

    @Test
    fun hiddenApiFailureRecognizesMissingMethodAndLinkageErrors() {
        assertTrue(AapTransportPlan.isHiddenApiAccessFailure(NoSuchMethodException("missing")))
        assertTrue(
            AapTransportPlan.isHiddenApiAccessFailure(
                ExceptionInInitializerError("hidden API library failed")
            )
        )
        assertTrue(
            AapTransportPlan.isHiddenApiAccessFailure(
                NoClassDefFoundError("hidden API library unavailable")
            )
        )
    }

    @Test
    fun hiddenApiFailureDoesNotClassifyOrdinaryIoFailure() {
        assertFalse(AapTransportPlan.isHiddenApiAccessFailure(java.io.IOException("temporary")))
    }
}
