package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconParserTest {

    private fun frame(status: Int, lidByte: Int): ByteArray = ByteArray(27).apply {
        this[0] = 0x07
        this[1] = 0x19
        this[2] = 0x01
        this[3] = 0x1B
        this[4] = 0x20
        this[5] = status.toByte()
        this[6] = 0xFF.toByte()
        this[7] = 0x04
        this[8] = lidByte.toByte()
    }

    @Test
    fun distinguishesTheBroadcastingPodFromThePodLeftInCase() {
        assertTrue(BeaconParser.decodeCaseState(status = 0x40, lidByte = 0).podsInCase)
        assertTrue(BeaconParser.decodeCaseState(status = 0x04, lidByte = 0).podsInCase)
        assertFalse(BeaconParser.decodeCaseState(status = 0x10, lidByte = 0).podsInCase)
        assertFalse(BeaconParser.decodeCaseState(status = 0x40, lidByte = 0).bothPodsInCase)
        assertTrue(BeaconParser.decodeCaseState(status = 0x04, lidByte = 0).bothPodsInCase)
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

    @Test
    fun screenshotVectorIsBothPodsInCaseWithOpenLidAndNeitherInEar() {
        val beacon = BeaconParser.parseManufacturerData(
            frame(status = 0x35, lidByte = 0x13),
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -65,
            timestamp = 1L,
        )!!

        assertTrue(beacon.bothPodsInCase)
        assertEquals(BeaconParser.LidState.OPEN, beacon.lidState)
        assertFalse(beacon.leftInEar)
        assertFalse(beacon.rightInEar)
    }

    @Test
    fun knownAirPods4AncVectorHasBothPodsInEar() {
        val beacon = BeaconParser.parseManufacturerData(
            frame(status = 0x0B, lidByte = 0),
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -65,
        )!!

        assertFalse(beacon.bothPodsInCase)
        assertTrue(beacon.leftInEar)
        assertTrue(beacon.rightInEar)
    }

    @Test
    fun rejectsShortOrWrongPrefixTypeSevenFrames() {
        assertEquals(
            null,
            BeaconParser.parseManufacturerData(
                ByteArray(11).apply { this[0] = 0x07 },
                address = "?",
                rssi = -65,
            ),
        )
        assertEquals(
            null,
            BeaconParser.parseManufacturerData(
                frame(status = 0x35, lidByte = 0x13).apply { this[2] = 0x00 },
                address = "?",
                rssi = -65,
            ),
        )
    }
}
