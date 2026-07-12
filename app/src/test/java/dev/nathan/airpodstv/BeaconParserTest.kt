package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconParserTest {

    @Test
    fun distinguishesTheBroadcastingPodFromThePodLeftInCase() {
        assertTrue(BeaconParser.decodeCaseState(status = 0x40, lidByte = 0).podsInCase)
        assertTrue(BeaconParser.decodeCaseState(status = 0x04, lidByte = 0).podsInCase)
        assertFalse(BeaconParser.decodeCaseState(status = 0x10, lidByte = 0).podsInCase)
    }

    @Test
    fun onlyReliableCaseFramesDriveLidState() {
        assertEquals(
            BeaconParser.LidState.OPEN,
            BeaconParser.decodeCaseState(status = 0x40, lidByte = 0x30).lidState,
        )
        assertEquals(
            BeaconParser.LidState.CLOSED,
            BeaconParser.decodeCaseState(status = 0x04, lidByte = 0x38).lidState,
        )
        assertEquals(
            BeaconParser.LidState.UNKNOWN,
            BeaconParser.decodeCaseState(status = 0x10, lidByte = 0x30).lidState,
        )
    }
}
