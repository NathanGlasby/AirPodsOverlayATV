package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedReactionControllerTest {
    @Test
    fun bleEarChangesRequireTwoMatchingSamples() {
        val controller = controller()

        assertFalse(controller.onBleSample(ble(inEarCount = 2, nowMs = 100L)).hasMediaAction())
        assertFalse(controller.onBleSample(ble(inEarCount = 2, nowMs = 200L)).hasMediaAction())
        assertFalse(controller.onBleSample(ble(inEarCount = 1, nowMs = 300L)).hasMediaAction())
        assertTrue(
            ConnectedReactionController.Action.PAUSE_MEDIA in
                controller.onBleSample(ble(inEarCount = 1, nowMs = 400L))
        )
        assertFalse(controller.onBleSample(ble(inEarCount = 2, nowMs = 500L)).hasMediaAction())
        assertTrue(
            ConnectedReactionController.Action.PLAY_MEDIA in
                controller.onBleSample(ble(inEarCount = 2, nowMs = 600L))
        )
    }

    @Test
    fun aapPlacementTakesImmediateEarAuthority() {
        val controller = controller()
        controller.onAapSample(aap(inEarCount = 2, bothPodsInCase = false, nowMs = 100L))

        repeat(2) {
            assertFalse(
                ConnectedReactionController.Action.PAUSE_MEDIA in
                    controller.onBleSample(ble(inEarCount = 1, nowMs = 200L + it))
            )
        }
        assertTrue(
            ConnectedReactionController.Action.PAUSE_MEDIA in
                controller.onAapSample(
                    aap(inEarCount = 1, bothPodsInCase = false, nowMs = 300L)
                )
        )
    }

    @Test
    fun bleEarSamplesResumeAfterAapBecomesUnavailable() {
        val controller = controller()
        controller.onAapSample(aap(inEarCount = 2, bothPodsInCase = false, nowMs = 100L))
        controller.onAapUnavailable()

        assertFalse(controller.onBleSample(ble(inEarCount = 1, nowMs = 200L)).hasMediaAction())
        assertTrue(
            ConnectedReactionController.Action.PAUSE_MEDIA in
                controller.onBleSample(ble(inEarCount = 1, nowMs = 300L))
        )
    }

    @Test
    fun aapHandoffRequiresFreshBleFramesAfterTheSameCountWasPrimed() {
        val controller = controller()
        controller.onBleSample(ble(inEarCount = 2, nowMs = 100L))
        controller.onBleSample(ble(inEarCount = 2, nowMs = 200L))
        assertTrue(
            ConnectedReactionController.Action.PAUSE_MEDIA in
                controller.onAapSample(
                    aap(inEarCount = 1, bothPodsInCase = false, nowMs = 300L)
                )
        )

        controller.onAapUnavailable()

        assertFalse(controller.onBleSample(ble(inEarCount = 2, nowMs = 400L)).hasMediaAction())
        assertTrue(
            ConnectedReactionController.Action.PLAY_MEDIA in
                controller.onBleSample(ble(inEarCount = 2, nowMs = 500L))
        )
    }

    @Test
    fun aapHandoffClearsThePrimedBleOutsideCaseStreak() {
        val controller = controller()
        controller.onBleSample(ble(bothPodsInCase = false, nowMs = 100L))
        controller.onAapSample(aap(bothPodsInCase = true, nowMs = 200L))

        controller.onAapUnavailable()

        assertFalse(
            ConnectedReactionController.Action.RESET_LID_DISCONNECT in
                controller.onBleSample(ble(bothPodsInCase = false, nowMs = 300L))
        )
        assertTrue(
            ConnectedReactionController.Action.RESET_LID_DISCONNECT in
                controller.onBleSample(ble(bothPodsInCase = false, nowMs = 400L))
        )
    }

    @Test
    fun explicitClosedCaseRequestsDisconnectOnce() {
        val controller = controller()
        val first = controller.onBleSample(
            ble(bothPodsInCase = true, lidClosed = true, nowMs = 100L)
        )
        val second = controller.onBleSample(
            ble(bothPodsInCase = true, lidClosed = true, nowMs = 200L)
        )

        assertEquals(
            1,
            first.count { it == ConnectedReactionController.Action.DISCONNECT_CLOSED_CASE },
        )
        assertFalse(ConnectedReactionController.Action.DISCONNECT_CLOSED_CASE in second)
    }

    @Test
    fun aapCasePlacementCanUseBleLidCloseEdge() {
        val controller = controller()
        controller.onAapSample(aap(bothPodsInCase = true, nowMs = 100L))

        val actions = controller.onBleSample(
            ble(bothPodsInCase = false, lidClosed = true, nowMs = 200L)
        )

        assertTrue(ConnectedReactionController.Action.DISCONNECT_CLOSED_CASE in actions)
    }

    @Test
    fun aapOutsideCasePlacementOverridesBleCaseBits() {
        val controller = controller()
        controller.onAapSample(aap(bothPodsInCase = false, nowMs = 100L))

        val actions = controller.onBleSample(
            ble(bothPodsInCase = true, lidClosed = true, nowMs = 200L)
        )

        assertFalse(ConnectedReactionController.Action.DISCONNECT_CLOSED_CASE in actions)
    }

    @Test
    fun twoBleOutsideCaseSamplesResetDisconnectCycleOnce() {
        val controller = controller()
        controller.onBleSample(ble(bothPodsInCase = true, nowMs = 100L))

        assertFalse(
            ConnectedReactionController.Action.RESET_LID_DISCONNECT in
                controller.onBleSample(ble(bothPodsInCase = false, nowMs = 200L))
        )
        assertTrue(
            ConnectedReactionController.Action.RESET_LID_DISCONNECT in
                controller.onBleSample(ble(bothPodsInCase = false, nowMs = 300L))
        )
        assertFalse(
            ConnectedReactionController.Action.RESET_LID_DISCONNECT in
                controller.onBleSample(ble(bothPodsInCase = false, nowMs = 400L))
        )
    }

    @Test
    fun armedCaseDisconnectsAfterAllowedSilence() {
        val controller = controller(silenceTimeoutMs = 5_000L)
        controller.onBleSample(ble(bothPodsInCase = true, nowMs = 1_000L))

        assertTrue(controller.onTime(time(nowMs = 5_999L)).isEmpty())
        assertTrue(
            ConnectedReactionController.Action.DISCONNECT_SILENT_CASE in
                controller.onTime(time(nowMs = 6_000L))
        )
    }

    @Test
    fun disabledSilenceCheckDoesNotConsumeDisconnect() {
        val controller = controller(silenceTimeoutMs = 5_000L)
        controller.onBleSample(ble(bothPodsInCase = true, nowMs = 1_000L))

        assertTrue(
            controller.onTime(time(nowMs = 6_000L, autoDisconnectEnabled = false)).isEmpty()
        )
        assertTrue(
            ConnectedReactionController.Action.DISCONNECT_SILENT_CASE in
                controller.onTime(time(nowMs = 6_001L))
        )
    }

    @Test
    fun resetClearsEarAndCaseState() {
        val controller = controller(silenceTimeoutMs = 1_000L)
        controller.onAapSample(aap(inEarCount = 2, bothPodsInCase = true, nowMs = 100L))

        controller.reset()

        assertTrue(controller.onTime(time(nowMs = 2_000L)).isEmpty())
        assertFalse(
            ConnectedReactionController.Action.PAUSE_MEDIA in
                controller.onAapSample(
                    aap(inEarCount = 1, bothPodsInCase = false, nowMs = 2_100L)
                )
        )
    }

    private fun controller(silenceTimeoutMs: Long = 5_000L) =
        ConnectedReactionController(silenceTimeoutMs)

    private fun ble(
        inEarCount: Int = 0,
        bothPodsInCase: Boolean = false,
        lidClosed: Boolean = false,
        playbackActive: Boolean = true,
        connected: Boolean = true,
        autoPauseEnabled: Boolean = true,
        autoDisconnectEnabled: Boolean = true,
        nowMs: Long,
    ) = ConnectedReactionController.BleSample(
        inEarCount = inEarCount,
        bothPodsInCase = bothPodsInCase,
        lidClosed = lidClosed,
        playbackActive = playbackActive,
        connected = connected,
        autoPauseEnabled = autoPauseEnabled,
        autoDisconnectEnabled = autoDisconnectEnabled,
        nowMs = nowMs,
    )

    private fun aap(
        inEarCount: Int = 0,
        bothPodsInCase: Boolean = false,
        playbackActive: Boolean = true,
        connected: Boolean = true,
        autoPauseEnabled: Boolean = true,
        nowMs: Long,
    ) = ConnectedReactionController.AapSample(
        inEarCount = inEarCount,
        bothPodsInCase = bothPodsInCase,
        playbackActive = playbackActive,
        connected = connected,
        autoPauseEnabled = autoPauseEnabled,
        nowMs = nowMs,
    )

    private fun time(
        nowMs: Long,
        connected: Boolean = true,
        silenceChecksAllowed: Boolean = true,
        autoDisconnectEnabled: Boolean = true,
    ) = ConnectedReactionController.TimeInput(
        nowMs = nowMs,
        connected = connected,
        silenceChecksAllowed = silenceChecksAllowed,
        autoDisconnectEnabled = autoDisconnectEnabled,
    )

    private fun List<ConnectedReactionController.Action>.hasMediaAction(): Boolean = any {
        it == ConnectedReactionController.Action.PAUSE_MEDIA ||
            it == ConnectedReactionController.Action.PLAY_MEDIA
    }
}
