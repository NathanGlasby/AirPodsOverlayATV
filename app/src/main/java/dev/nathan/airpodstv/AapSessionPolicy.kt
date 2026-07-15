package dev.nathan.airpodstv

/**
 * Coordinates whether an AAP client should be started, retained, or stopped.
 *
 * The owner calls [onFailure] after a failed or unexpectedly closed attempt and
 * [onActive] after a session becomes active. Time is supplied by the owner so this
 * policy remains independent of Android clocks.
 */
internal class AapSessionPolicy(
    private val initialRetryDelayMs: Long = 3_000L,
    private val maxRetryDelayMs: Long = 30_000L,
) {

    enum class Action { START, STOP, NONE }

    var isActive: Boolean = false
        private set

    var retryDeadlineMs: Long? = null
        private set

    private var nextRetryDelayMs = initialRetryDelayMs

    init {
        require(initialRetryDelayMs > 0L) { "initialRetryDelayMs must be positive" }
        require(maxRetryDelayMs >= initialRetryDelayMs) {
            "maxRetryDelayMs must be at least initialRetryDelayMs"
        }
    }

    fun decide(
        enabled: Boolean,
        connected: Boolean,
        clientPresent: Boolean,
        nowMs: Long,
    ): Action {
        if (!enabled || !connected) {
            reset()
            return if (clientPresent) Action.STOP else Action.NONE
        }

        if (clientPresent) return Action.NONE

        val retryAt = retryDeadlineMs
        return if (retryAt == null || nowMs >= retryAt) Action.START else Action.NONE
    }

    fun onFailure(nowMs: Long) {
        isActive = false
        retryDeadlineMs = saturatedAdd(nowMs, nextRetryDelayMs)
        nextRetryDelayMs = minOf(saturatedDouble(nextRetryDelayMs), maxRetryDelayMs)
    }

    fun onActive() {
        isActive = true
        retryDeadlineMs = null
        nextRetryDelayMs = initialRetryDelayMs
    }

    fun reset() {
        isActive = false
        retryDeadlineMs = null
        nextRetryDelayMs = initialRetryDelayMs
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatedDouble(value: Long): Long =
        if (value > Long.MAX_VALUE / 2L) Long.MAX_VALUE else value * 2L
}
