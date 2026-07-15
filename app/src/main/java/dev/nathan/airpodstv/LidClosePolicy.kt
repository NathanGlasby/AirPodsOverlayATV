package dev.nathan.airpodstv

/**
 * Turns reliable case signals, or their disappearance, into a one-shot disconnect.
 *
 * Silence is meaningful only after a signal has confirmed that both pods are in the
 * case. Removing either pod disarms the policy and permits a future case-close cycle.
 */
internal class LidClosePolicy(private val silenceTimeoutMs: Long) {

    enum class Action { DISCONNECT, NONE }

    private var armed = false
    private var disconnectEmitted = false
    private var lastSignalAtMs: Long? = null

    init {
        require(silenceTimeoutMs > 0L) { "silenceTimeoutMs must be positive" }
    }

    fun onSignal(
        bothPodsInCase: Boolean,
        explicitlyClosed: Boolean,
        nowMs: Long,
    ): Action {
        if (!bothPodsInCase) {
            reset()
            return Action.NONE
        }

        armed = true
        lastSignalAtMs = nowMs
        return emitDisconnectIf(explicitlyClosed)
    }

    fun onTime(nowMs: Long): Action {
        val lastSignal = lastSignalAtMs ?: return Action.NONE
        val silentLongEnough = nowMs - lastSignal >= silenceTimeoutMs
        return emitDisconnectIf(armed && silentLongEnough)
    }

    fun reset() {
        armed = false
        disconnectEmitted = false
        lastSignalAtMs = null
    }

    private fun emitDisconnectIf(shouldDisconnect: Boolean): Action {
        if (!shouldDisconnect || disconnectEmitted) return Action.NONE
        disconnectEmitted = true
        return Action.DISCONNECT
    }
}
