package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupSessionTest {

    @Test
    fun dismissalSuppressesCurrentOpenSessionAndCloseRearmsNextOpen() {
        val session = PopupSession(resetAfterMs = 8_000L)

        assertEquals(PopupSession.Event.OFFER_POPUP, session.onBeacon(BeaconParser.LidState.OPEN, 1_000L))
        session.suppress()
        assertEquals(PopupSession.Event.NONE, session.onBeacon(BeaconParser.LidState.OPEN, 2_000L))

        assertEquals(PopupSession.Event.LID_CLOSED, session.onBeacon(BeaconParser.LidState.CLOSED, 3_000L))
        assertFalse(session.suppressed)
        assertEquals(PopupSession.Event.OFFER_POPUP, session.onBeacon(BeaconParser.LidState.OPEN, 4_000L))
    }

    @Test
    fun continuousClosedBeaconsStayRearmedWithoutRepeatedCloseActions() {
        val session = PopupSession(resetAfterMs = 8_000L)

        assertEquals(PopupSession.Event.LID_CLOSED, session.onBeacon(BeaconParser.LidState.CLOSED, 1_000L))
        assertEquals(PopupSession.Event.NONE, session.onBeacon(BeaconParser.LidState.CLOSED, 2_000L))
        assertFalse(session.suppressed)
    }

    @Test
    fun unknownLidOffersOnlyAtStartOfFreshBeaconSession() {
        val session = PopupSession(resetAfterMs = 8_000L)

        assertEquals(PopupSession.Event.OFFER_POPUP, session.onBeacon(BeaconParser.LidState.UNKNOWN, 1_000L))
        session.suppress()
        assertEquals(PopupSession.Event.NONE, session.onBeacon(BeaconParser.LidState.UNKNOWN, 2_000L))
        assertEquals(PopupSession.Event.OFFER_POPUP, session.onBeacon(BeaconParser.LidState.UNKNOWN, 11_000L))
    }

    @Test
    fun onePodUnknownTransitionAfterContinuousClosedBeaconsOffersPopupOnce() {
        val session = PopupSession(resetAfterMs = 8_000L)

        session.onBeacon(BeaconParser.LidState.CLOSED, 1_000L)
        session.onBeacon(BeaconParser.LidState.CLOSED, 2_000L)
        assertEquals(PopupSession.Event.OFFER_POPUP, session.onBeacon(BeaconParser.LidState.UNKNOWN, 3_000L))
        session.suppress()
        assertEquals(PopupSession.Event.NONE, session.onBeacon(BeaconParser.LidState.UNKNOWN, 4_000L))
    }

    @Test
    fun freshSessionDoesNotReuseStaleClosedState() {
        val session = PopupSession(resetAfterMs = 8_000L)

        session.onBeacon(BeaconParser.LidState.CLOSED, 1_000L)
        assertEquals(PopupSession.Event.OFFER_POPUP, session.onBeacon(BeaconParser.LidState.UNKNOWN, 10_000L))
        assertEquals(PopupSession.Event.LID_CLOSED, session.onBeacon(BeaconParser.LidState.CLOSED, 11_000L))
    }

    @Test
    fun silenceRearmsSuppressedSession() {
        val session = PopupSession(resetAfterMs = 8_000L)
        session.onBeacon(BeaconParser.LidState.OPEN, 1_000L)
        session.suppress()

        assertFalse(session.rearmIfSilent(9_000L))
        assertTrue(session.rearmIfSilent(9_001L))
        assertFalse(session.suppressed)
    }
}
