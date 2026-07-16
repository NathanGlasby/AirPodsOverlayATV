package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Test

class EarPausePolicyTest {

    @Test
    fun pausesOnFirstDecreaseAndOnlyResumesItsOwnPause() {
        val policy = EarPausePolicy()

        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
        assertEquals(EarPausePolicy.Action.PAUSE, policy.onInEarCount(1))
        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(0))
        assertEquals(EarPausePolicy.Action.PLAY, policy.onInEarCount(1))
        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
    }

    @Test
    fun increaseWithoutPolicyPauseDoesNotPlay() {
        val policy = EarPausePolicy()

        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(0))
        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(1))
        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
    }

    @Test
    fun alreadyPausedPlaybackIsNeverClaimedOrResumed() {
        val policy = EarPausePolicy()

        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
        assertEquals(
            EarPausePolicy.Action.NONE,
            policy.onInEarCount(1, playbackActive = false),
        )
        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
    }

    @Test
    fun inactivePlaybackDoesNotConsumeTheRemovalEdge() {
        val policy = EarPausePolicy()

        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
        assertEquals(
            EarPausePolicy.Action.NONE,
            policy.onInEarCount(1, playbackActive = false),
        )
        assertEquals(
            EarPausePolicy.Action.PAUSE,
            policy.onInEarCount(1, playbackActive = true),
        )
    }

    @Test
    fun resetClearsBaselineAndPauseOwnership() {
        val policy = EarPausePolicy()
        policy.onInEarCount(2)
        assertEquals(EarPausePolicy.Action.PAUSE, policy.onInEarCount(1))

        policy.reset()

        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(2))
        assertEquals(EarPausePolicy.Action.PAUSE, policy.onInEarCount(1))
    }

    @Test
    fun rebaselinePreservesPauseOwnershipAcrossSourceHandoff() {
        val policy = EarPausePolicy()
        policy.onInEarCount(2)
        assertEquals(EarPausePolicy.Action.PAUSE, policy.onInEarCount(1))

        policy.rebaseline()

        assertEquals(EarPausePolicy.Action.NONE, policy.onInEarCount(0))
        assertEquals(EarPausePolicy.Action.PLAY, policy.onInEarCount(1))
    }
}
