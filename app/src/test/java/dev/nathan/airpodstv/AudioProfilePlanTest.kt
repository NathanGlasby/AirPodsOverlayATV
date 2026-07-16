package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfilePlanTest {
    @Test
    fun a2dpOnlyTvIsReady() {
        val available = setOf(AudioProfilePlan.Profile.A2DP)

        assertTrue(AudioProfilePlan.isReady(available))
        assertEquals(
            listOf(AudioProfilePlan.Profile.A2DP),
            AudioProfilePlan.operationTargets(available),
        )
    }

    @Test
    fun headsetWithoutA2dpIsNotReady() {
        val available = setOf(AudioProfilePlan.Profile.HEADSET)

        assertFalse(AudioProfilePlan.isReady(available))
        assertTrue(AudioProfilePlan.operationTargets(available).isEmpty())
    }

    @Test
    fun availableHeadsetIsAnOptionalPolicyTarget() {
        val available = setOf(
            AudioProfilePlan.Profile.HEADSET,
            AudioProfilePlan.Profile.A2DP,
        )

        assertEquals(
            listOf(
                AudioProfilePlan.Profile.A2DP,
                AudioProfilePlan.Profile.HEADSET,
            ),
            AudioProfilePlan.operationTargets(available),
        )
    }

    @Test
    fun headsetConnectionDoesNotCountAsTvAudio() {
        assertFalse(
            AudioProfilePlan.isAudioConnected(setOf(AudioProfilePlan.Profile.HEADSET))
        )
        assertTrue(
            AudioProfilePlan.isAudioConnected(setOf(AudioProfilePlan.Profile.A2DP))
        )
    }

    @Test
    fun policyWaitsForAnOptionalProfileRequestToSettle() {
        val a2dpOnly = setOf(AudioProfilePlan.Profile.A2DP)

        assertFalse(
            AudioProfilePlan.canApplyPolicy(
                available = a2dpOnly,
                optionalProfilePending = true,
                waitExpired = false,
            )
        )
        assertTrue(
            AudioProfilePlan.canApplyPolicy(
                available = a2dpOnly,
                optionalProfilePending = true,
                waitExpired = true,
            )
        )
    }

    @Test
    fun policyAppliesAsSoonAsBothProfileRequestsSettle() {
        assertTrue(
            AudioProfilePlan.canApplyPolicy(
                available = setOf(
                    AudioProfilePlan.Profile.A2DP,
                    AudioProfilePlan.Profile.HEADSET,
                ),
                optionalProfilePending = false,
                waitExpired = false,
            )
        )
    }
}
