package dev.nathan.airpodstv

/** Pure state machine for connected-device ear and case reactions. */
internal class ConnectedReactionController(
    silenceTimeoutMs: Long,
) {
    enum class Action {
        PAUSE_MEDIA,
        PLAY_MEDIA,
        DISCONNECT_CLOSED_CASE,
        DISCONNECT_SILENT_CASE,
        RESET_LID_DISCONNECT,
    }

    data class BleSample(
        val inEarCount: Int,
        val bothPodsInCase: Boolean,
        val lidClosed: Boolean,
        val playbackActive: Boolean,
        val connected: Boolean,
        val autoPauseEnabled: Boolean,
        val autoDisconnectEnabled: Boolean,
        val nowMs: Long,
    )

    data class AapSample(
        val inEarCount: Int,
        val bothPodsInCase: Boolean,
        val playbackActive: Boolean,
        val connected: Boolean,
        val autoPauseEnabled: Boolean,
        val nowMs: Long,
    )

    data class TimeInput(
        val nowMs: Long,
        val connected: Boolean,
        val silenceChecksAllowed: Boolean,
        val autoDisconnectEnabled: Boolean,
    )

    private val earPausePolicy = EarPausePolicy()
    private val lidClosePolicy = LidClosePolicy(silenceTimeoutMs)
    private var aapEarAuthoritative = false
    private var aapBothPodsInCase = false
    private var bleEarStreakValue: Int? = null
    private var bleEarStreak = 0
    private var podsOutsideCaseStreak = 0

    fun onBleSample(input: BleSample): List<Action> {
        require(input.inEarCount in 0..2)
        val actions = mutableListOf<Action>()

        if (!aapEarAuthoritative) {
            mediaAction(
                inEarCount = input.inEarCount,
                playbackActive = input.playbackActive,
                connected = input.connected,
                autoPauseEnabled = input.autoPauseEnabled,
                requireStableBleSample = true,
            )?.let(actions::add)
        }

        val lidAction = when {
            aapEarAuthoritative && aapBothPodsInCase -> {
                podsOutsideCaseStreak = 0
                lidClosePolicy.onSignal(
                    bothPodsInCase = true,
                    explicitlyClosed = input.lidClosed,
                    nowMs = input.nowMs,
                )
            }
            aapEarAuthoritative -> LidClosePolicy.Action.NONE
            input.bothPodsInCase -> {
                podsOutsideCaseStreak = 0
                lidClosePolicy.onSignal(
                    bothPodsInCase = true,
                    explicitlyClosed = input.lidClosed,
                    nowMs = input.nowMs,
                )
            }
            else -> {
                if (podsOutsideCaseStreak < 2) {
                    podsOutsideCaseStreak++
                    if (podsOutsideCaseStreak == 2) {
                        lidClosePolicy.reset()
                        actions += Action.RESET_LID_DISCONNECT
                    }
                }
                LidClosePolicy.Action.NONE
            }
        }
        if (input.autoDisconnectEnabled && lidAction == LidClosePolicy.Action.DISCONNECT) {
            actions += Action.DISCONNECT_CLOSED_CASE
        }
        return actions
    }

    fun onAapSample(input: AapSample): List<Action> {
        require(input.inEarCount in 0..2)
        val actions = mutableListOf<Action>()
        aapEarAuthoritative = true
        mediaAction(
            inEarCount = input.inEarCount,
            playbackActive = input.playbackActive,
            connected = input.connected,
            autoPauseEnabled = input.autoPauseEnabled,
            requireStableBleSample = false,
        )?.let(actions::add)

        aapBothPodsInCase = input.bothPodsInCase
        if (input.bothPodsInCase) {
            lidClosePolicy.onSignal(
                bothPodsInCase = true,
                explicitlyClosed = false,
                nowMs = input.nowMs,
            )
        } else {
            lidClosePolicy.reset()
            podsOutsideCaseStreak = 0
            actions += Action.RESET_LID_DISCONNECT
        }
        return actions
    }

    fun onTime(input: TimeInput): List<Action> {
        if (!input.silenceChecksAllowed || !input.autoDisconnectEnabled || !input.connected) {
            return emptyList()
        }
        return if (lidClosePolicy.onTime(input.nowMs) == LidClosePolicy.Action.DISCONNECT) {
            listOf(Action.DISCONNECT_SILENT_CASE)
        } else {
            emptyList()
        }
    }

    fun onAapUnavailable() {
        aapEarAuthoritative = false
        aapBothPodsInCase = false
        bleEarStreakValue = null
        bleEarStreak = 0
        podsOutsideCaseStreak = 0
    }

    fun onScannerUnavailable() {
        lidClosePolicy.reset()
        podsOutsideCaseStreak = 0
    }

    fun reset() {
        earPausePolicy.reset()
        lidClosePolicy.reset()
        aapEarAuthoritative = false
        aapBothPodsInCase = false
        bleEarStreakValue = null
        bleEarStreak = 0
        podsOutsideCaseStreak = 0
    }

    private fun mediaAction(
        inEarCount: Int,
        playbackActive: Boolean,
        connected: Boolean,
        autoPauseEnabled: Boolean,
        requireStableBleSample: Boolean,
    ): Action? {
        if (!autoPauseEnabled || !connected) {
            earPausePolicy.reset()
            return null
        }
        if (requireStableBleSample) {
            if (inEarCount == bleEarStreakValue) {
                bleEarStreak = minOf(bleEarStreak + 1, 2)
            } else {
                bleEarStreakValue = inEarCount
                bleEarStreak = 1
            }
            if (bleEarStreak < 2) return null
        }
        return when (earPausePolicy.onInEarCount(inEarCount, playbackActive)) {
            EarPausePolicy.Action.PAUSE -> Action.PAUSE_MEDIA
            EarPausePolicy.Action.PLAY -> Action.PLAY_MEDIA
            EarPausePolicy.Action.NONE -> null
        }
    }
}
