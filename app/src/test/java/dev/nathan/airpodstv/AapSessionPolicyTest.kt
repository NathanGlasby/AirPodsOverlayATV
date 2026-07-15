package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapSessionPolicyTest {

    @Test
    fun startsWhenEnabledConnectedAndNoClientExists() {
        val policy = AapSessionPolicy()

        assertEquals(
            AapSessionPolicy.Action.START,
            policy.decide(enabled = true, connected = true, clientPresent = false, nowMs = 1_000L),
        )
        assertEquals(
            AapSessionPolicy.Action.NONE,
            policy.decide(enabled = true, connected = true, clientPresent = true, nowMs = 1_000L),
        )
    }

    @Test
    fun stopsExistingClientWhenDisabledOrDisconnected() {
        val policy = AapSessionPolicy()

        assertEquals(
            AapSessionPolicy.Action.STOP,
            policy.decide(enabled = false, connected = true, clientPresent = true, nowMs = 1_000L),
        )
        assertEquals(
            AapSessionPolicy.Action.STOP,
            policy.decide(enabled = true, connected = false, clientPresent = true, nowMs = 1_000L),
        )
    }

    @Test
    fun failuresUseBoundedExponentialBackoff() {
        val policy = AapSessionPolicy(initialRetryDelayMs = 1_000L, maxRetryDelayMs = 4_000L)

        policy.onFailure(nowMs = 10_000L)
        assertEquals(11_000L, policy.retryDeadlineMs)
        assertEquals(AapSessionPolicy.Action.NONE, policy.decide(true, true, false, 10_999L))
        assertEquals(AapSessionPolicy.Action.START, policy.decide(true, true, false, 11_000L))

        policy.onFailure(nowMs = 20_000L)
        assertEquals(22_000L, policy.retryDeadlineMs)
        policy.onFailure(nowMs = 30_000L)
        assertEquals(34_000L, policy.retryDeadlineMs)
        policy.onFailure(nowMs = 40_000L)
        assertEquals(44_000L, policy.retryDeadlineMs)
    }

    @Test
    fun activeSessionClearsBackoffAndResetClearsActivity() {
        val policy = AapSessionPolicy(initialRetryDelayMs = 1_000L, maxRetryDelayMs = 4_000L)
        policy.onFailure(nowMs = 1_000L)

        policy.onActive()

        assertTrue(policy.isActive)
        assertNull(policy.retryDeadlineMs)
        assertEquals(AapSessionPolicy.Action.NONE, policy.decide(true, true, true, 1_000L))

        policy.reset()

        assertFalse(policy.isActive)
        assertNull(policy.retryDeadlineMs)
        assertEquals(AapSessionPolicy.Action.START, policy.decide(true, true, false, 1_000L))
    }

    @Test
    fun disablingResetsRetryBackoff() {
        val policy = AapSessionPolicy(initialRetryDelayMs = 1_000L, maxRetryDelayMs = 4_000L)
        policy.onFailure(nowMs = 1_000L)
        policy.onFailure(nowMs = 2_000L)

        assertEquals(AapSessionPolicy.Action.NONE, policy.decide(false, true, false, 2_000L))
        assertNull(policy.retryDeadlineMs)

        policy.onFailure(nowMs = 10_000L)
        assertEquals(11_000L, policy.retryDeadlineMs)
    }
}
