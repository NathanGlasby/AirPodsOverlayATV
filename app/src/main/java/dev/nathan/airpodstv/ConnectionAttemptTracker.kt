package dev.nathan.airpodstv

/**
 * Tracks the one popup-approved Bluetooth connection that may bypass auto-connect blocking.
 * State access is synchronized because profile callbacks may move off the main thread.
 */
internal class ConnectionAttemptTracker(
    private val timeoutMs: Long,
) {
    init {
        require(timeoutMs > 0L)
    }

    data class Token internal constructor(
        val generation: Long,
        val address: String,
    )

    private data class Attempt(
        val token: Token,
        val startedAtMs: Long,
        var aclObserved: Boolean = false,
        var callbackFinished: Boolean = false,
    )

    private var nextGeneration = 0L
    private var active: Attempt? = null

    @Synchronized
    fun begin(address: String, nowMs: Long): Token {
        require(address.isNotBlank())
        val token = Token(++nextGeneration, address)
        active = Attempt(token, nowMs)
        return token
    }

    /** Returns true only when this ACL belongs to the active popup-approved attempt. */
    @Synchronized
    fun onAclConnected(address: String, nowMs: Long): Boolean {
        val attempt = freshAttempt(nowMs) ?: return false
        if (!sameAddress(attempt.token.address, address)) return false
        attempt.aclObserved = true
        if (attempt.callbackFinished) active = null
        return true
    }

    /** Cancels only a matching attempt; another bonded device cannot invalidate it. */
    @Synchronized
    fun onAclDisconnected(address: String, nowMs: Long): Boolean {
        val attempt = freshAttempt(nowMs) ?: return false
        if (!sameAddress(attempt.token.address, address)) return false
        active = null
        return true
    }

    /**
     * Accepts a ProfileConnector callback only for the active token and selected device.
     * A successful callback remains valid until its matching ACL arrives or the window expires.
     */
    @Synchronized
    fun acceptCallback(
        token: Token,
        selectedAddress: String?,
        success: Boolean,
        nowMs: Long,
    ): Boolean {
        val attempt = freshAttempt(nowMs) ?: return false
        if (attempt.token != token || !sameAddress(token.address, selectedAddress)) return false
        attempt.callbackFinished = true
        if (!success || attempt.aclObserved) active = null
        return true
    }

    /** Dismissal cancels work still connecting, but not a confirmed result awaiting its ACL. */
    @Synchronized
    fun cancelPending() {
        if (active?.callbackFinished != true) active = null
    }

    @Synchronized
    fun cancelAll() {
        active = null
    }

    private fun freshAttempt(nowMs: Long): Attempt? {
        val attempt = active ?: return null
        val elapsed = nowMs - attempt.startedAtMs
        if (elapsed < 0L || elapsed >= timeoutMs) {
            active = null
            return null
        }
        return attempt
    }

    private fun sameAddress(expected: String, actual: String?): Boolean =
        actual != null && expected.equals(actual, ignoreCase = true)
}
