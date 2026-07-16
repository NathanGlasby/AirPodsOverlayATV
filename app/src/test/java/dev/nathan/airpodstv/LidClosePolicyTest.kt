package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Test

class LidClosePolicyTest {

    @Test
    fun explicitCloseDisconnectsOnceWhenBothPodsAreInCase() {
        val policy = LidClosePolicy(silenceTimeoutMs = 5_000L)

        assertEquals(
            LidClosePolicy.Action.DISCONNECT,
            policy.onSignal(bothPodsInCase = true, explicitlyClosed = true, nowMs = 1_000L),
        )
        assertEquals(
            LidClosePolicy.Action.NONE,
            policy.onSignal(bothPodsInCase = true, explicitlyClosed = true, nowMs = 2_000L),
        )
    }

    @Test
    fun silenceAfterArmedSignalDisconnectsAtDeadline() {
        val policy = LidClosePolicy(silenceTimeoutMs = 5_000L)
        policy.onSignal(bothPodsInCase = true, explicitlyClosed = false, nowMs = 1_000L)

        assertEquals(LidClosePolicy.Action.NONE, policy.onTime(5_999L))
        assertEquals(LidClosePolicy.Action.DISCONNECT, policy.onTime(6_000L))
        assertEquals(LidClosePolicy.Action.NONE, policy.onTime(20_000L))
    }

    @Test
    fun aNewSignalMovesTheSilenceDeadline() {
        val policy = LidClosePolicy(silenceTimeoutMs = 5_000L)
        policy.onSignal(bothPodsInCase = true, explicitlyClosed = false, nowMs = 1_000L)
        policy.onSignal(bothPodsInCase = true, explicitlyClosed = false, nowMs = 4_000L)

        assertEquals(LidClosePolicy.Action.NONE, policy.onTime(8_999L))
        assertEquals(LidClosePolicy.Action.DISCONNECT, policy.onTime(9_000L))
    }

    @Test
    fun removingAPodDisarmsUntilBothReturn() {
        val policy = LidClosePolicy(silenceTimeoutMs = 5_000L)
        policy.onSignal(bothPodsInCase = true, explicitlyClosed = false, nowMs = 1_000L)
        policy.onSignal(bothPodsInCase = false, explicitlyClosed = true, nowMs = 2_000L)

        assertEquals(LidClosePolicy.Action.NONE, policy.onTime(20_000L))
        assertEquals(
            LidClosePolicy.Action.DISCONNECT,
            policy.onSignal(bothPodsInCase = true, explicitlyClosed = true, nowMs = 21_000L),
        )
    }

    @Test
    fun resetDisarmsPolicy() {
        val policy = LidClosePolicy(silenceTimeoutMs = 5_000L)
        policy.onSignal(bothPodsInCase = true, explicitlyClosed = false, nowMs = 1_000L)

        policy.reset()

        assertEquals(LidClosePolicy.Action.NONE, policy.onTime(10_000L))
    }

    @Test
    fun transportFailureKeepsAnArmedSilenceDisconnectCycle() {
        val policy = LidClosePolicy(silenceTimeoutMs = 5_000L)
        policy.onSignal(bothPodsInCase = true, explicitlyClosed = false, nowMs = 1_000L)

        policy.onTransportUnavailable()

        assertEquals(LidClosePolicy.Action.NONE, policy.onTime(5_999L))
        assertEquals(LidClosePolicy.Action.DISCONNECT, policy.onTime(6_000L))
    }
}
