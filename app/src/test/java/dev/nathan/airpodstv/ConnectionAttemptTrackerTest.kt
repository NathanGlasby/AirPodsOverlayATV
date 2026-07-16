package dev.nathan.airpodstv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAttemptTrackerTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val otherAddress = "11:22:33:44:55:66"

    @Test
    fun sameAddressCallbackThenAclCompletesAttempt() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        assertTrue(tracker.acceptCallback(token, address, success = true, nowMs = 200L))
        assertTrue(tracker.onAclConnected(address, nowMs = 300L))
        assertFalse(tracker.onAclConnected(address, nowMs = 301L))
    }

    @Test
    fun sameAddressAclThenCallbackCompletesAttempt() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        assertTrue(tracker.onAclConnected(address, nowMs = 200L))
        assertTrue(tracker.acceptCallback(token, address, success = true, nowMs = 300L))
        assertFalse(tracker.acceptCallback(token, address, success = true, nowMs = 301L))
    }

    @Test
    fun wrongAddressEventsDoNotChangeActiveAttempt() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        assertFalse(tracker.onAclConnected(otherAddress, nowMs = 200L))
        assertFalse(tracker.onAclDisconnected(otherAddress, nowMs = 300L))
        assertTrue(tracker.acceptCallback(token, address, success = true, nowMs = 400L))
        assertTrue(tracker.onAclConnected(address.lowercase(), nowMs = 500L))
    }

    @Test
    fun callbackForAnotherSelectedDeviceIsIgnored() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        assertFalse(
            tracker.acceptCallback(token, otherAddress, success = false, nowMs = 200L)
        )
        assertTrue(tracker.acceptCallback(token, address, success = true, nowMs = 300L))
    }

    @Test
    fun staleGenerationCannotCompleteNewAttempt() {
        val tracker = tracker()
        val stale = tracker.begin(address, nowMs = 100L)
        val current = tracker.begin(address, nowMs = 200L)

        assertNotEquals(stale.generation, current.generation)
        assertFalse(tracker.acceptCallback(stale, address, success = false, nowMs = 300L))
        assertTrue(tracker.acceptCallback(current, address, success = true, nowMs = 400L))
    }

    @Test
    fun timeoutRejectsCallbackAndAclAtBoundary() {
        val callbackTracker = tracker(timeoutMs = 1_000L)
        val token = callbackTracker.begin(address, nowMs = 100L)

        assertFalse(
            callbackTracker.acceptCallback(token, address, success = true, nowMs = 1_100L)
        )

        val aclTracker = tracker(timeoutMs = 1_000L)
        aclTracker.begin(address, nowMs = 100L)
        assertFalse(aclTracker.onAclConnected(address, nowMs = 1_100L))
    }

    @Test
    fun matchingDisconnectCancelsAttempt() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        assertTrue(tracker.onAclDisconnected(address, nowMs = 200L))
        assertFalse(tracker.acceptCallback(token, address, success = true, nowMs = 300L))
    }

    @Test
    fun cancellationInvalidatesPendingAttempt() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        tracker.cancelAll()

        assertFalse(tracker.acceptCallback(token, address, success = true, nowMs = 200L))
        assertFalse(tracker.onAclConnected(address, nowMs = 200L))
    }

    @Test
    fun pendingDismissalCancelsButConfirmedCallbackSurvives() {
        val tracker = tracker()
        val pending = tracker.begin(address, nowMs = 100L)

        tracker.cancelPending()
        assertFalse(tracker.acceptCallback(pending, address, success = true, nowMs = 200L))

        val confirmed = tracker.begin(address, nowMs = 300L)
        assertTrue(tracker.acceptCallback(confirmed, address, success = true, nowMs = 400L))
        tracker.cancelPending()
        assertTrue(tracker.onAclConnected(address, nowMs = 500L))
    }

    @Test
    fun failedCallbackClearsAttempt() {
        val tracker = tracker()
        val token = tracker.begin(address, nowMs = 100L)

        assertTrue(tracker.acceptCallback(token, address, success = false, nowMs = 200L))
        assertFalse(tracker.onAclConnected(address, nowMs = 300L))
    }

    private fun tracker(timeoutMs: Long = 30_000L) = ConnectionAttemptTracker(timeoutMs)
}
