package dev.nathan.airpodstv

/**
 * Decides when an in-ear count transition should control media playback.
 *
 * The first observation establishes a baseline. A later decrease pauses once, and a
 * subsequent increase resumes only when this policy was responsible for the pause.
 */
internal class EarPausePolicy {

    enum class Action { PAUSE, PLAY, NONE }

    private var previousInEarCount: Int? = null
    private var pausedByPolicy = false

    fun onInEarCount(inEarCount: Int, playbackActive: Boolean = true): Action {
        require(inEarCount in 0..2) { "inEarCount must be between 0 and 2" }

        val previous = previousInEarCount
        if (previous == null) {
            previousInEarCount = inEarCount
            return Action.NONE
        }
        if (previous == inEarCount) return Action.NONE

        // AudioManager can briefly report inactive while a player changes focus. Do not
        // consume the removal edge; an identical later sample can retry the idempotent pause.
        if (inEarCount < previous && !pausedByPolicy && !playbackActive) return Action.NONE

        previousInEarCount = inEarCount

        return when {
            inEarCount < previous && !pausedByPolicy -> {
                pausedByPolicy = true
                Action.PAUSE
            }
            inEarCount > previous && pausedByPolicy -> {
                pausedByPolicy = false
                Action.PLAY
            }
            else -> Action.NONE
        }
    }

    fun reset() {
        previousInEarCount = null
        pausedByPolicy = false
    }

    /** Establishes the next count as a baseline without forgetting a pause we own. */
    fun rebaseline() {
        previousInEarCount = null
    }
}
