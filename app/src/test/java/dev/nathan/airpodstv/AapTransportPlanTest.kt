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
        assertEquals(8_000L, AapTransportPlan.timeoutMs())
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

    @Test
    fun allMissingPlatformApisAreUnsupported() {
        assertTrue(
            AapTransportPlan.isPlatformUnsupported(
                AapTransportPlan.forSdk(30).map {
                    AapTransportPlan.AttemptFailure.PLATFORM_API_UNAVAILABLE
                }
            )
        )
    }

    @Test
    fun android11AllTimeoutsRemainRetryable() {
        assertFalse(
            AapTransportPlan.isPlatformUnsupported(
                AapTransportPlan.forSdk(30).map { AapTransportPlan.AttemptFailure.TIMEOUT }
            )
        )
    }

    @Test
    fun ioFailuresAndConnectionRefusalsRemainRetryable() {
        assertFalse(
            AapTransportPlan.isPlatformUnsupported(
                listOf(
                    AapTransportPlan.AttemptFailure.CONNECTION_FAILURE,
                    AapTransportPlan.AttemptFailure.CONNECTION_FAILURE,
                )
            )
        )
    }

    @Test
    fun unavailableAndTimeoutFailuresRemainRetryable() {
        assertFalse(
            AapTransportPlan.isPlatformUnsupported(
                listOf(
                    AapTransportPlan.AttemptFailure.PLATFORM_API_UNAVAILABLE,
                    AapTransportPlan.AttemptFailure.TIMEOUT,
                )
            )
        )
    }

    @Test
    fun unavailableAndConnectionFailuresRemainRetryable() {
        assertFalse(
            AapTransportPlan.isPlatformUnsupported(
                listOf(
                    AapTransportPlan.AttemptFailure.PLATFORM_API_UNAVAILABLE,
                    AapTransportPlan.AttemptFailure.CONNECTION_FAILURE,
                )
            )
        )
    }

    @Test
    fun timeoutAndConnectionFailuresRemainRetryable() {
        assertFalse(
            AapTransportPlan.isPlatformUnsupported(
                listOf(
                    AapTransportPlan.AttemptFailure.TIMEOUT,
                    AapTransportPlan.AttemptFailure.CONNECTION_FAILURE,
                )
            )
        )
    }

    @Test
    fun emptyFailureListIsNotUnsupported() {
        assertFalse(AapTransportPlan.isPlatformUnsupported(emptyList()))
    }

    @Test
    fun socketCreationFailureClassificationKeepsOrdinaryErrorsRetryable() {
        assertEquals(
            AapTransportPlan.AttemptFailure.PLATFORM_API_UNAVAILABLE,
            AapTransportPlan.classifySocketCreationFailure(NoSuchMethodException("missing")),
        )
        assertEquals(
            AapTransportPlan.AttemptFailure.CONNECTION_FAILURE,
            AapTransportPlan.classifySocketCreationFailure(java.io.IOException("refused")),
        )
    }
}
