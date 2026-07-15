package dev.nathan.airpodstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapPacketTest {
    @Test
    fun parsesSuccessfulConnectResponse() {
        val raw = byteArrayOf(
            0x01, 0x00, 0x04, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        val packet = AapPacket.parse(raw) as AapPacket.ConnectResponse

        assertEquals(0x0004, packet.service)
        assertEquals(0, packet.status)
    }

    @Test
    fun parsesMessageWithoutInventingStreamLengths() {
        val payload = byteArrayOf(0x0D, 0x02, 0x00, 0x00, 0x00)
        val raw = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00) + payload

        val packet = AapPacket.parse(raw) as AapPacket.Message

        assertEquals(0x0009, packet.command)
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun preservesUnknownPacketTypeForDiagnostics() {
        val packet = AapPacket.parse(byteArrayOf(0x33, 0x00, 0x04, 0x00))

        assertTrue(packet is AapPacket.Other)
        assertEquals(0x0033, (packet as AapPacket.Other).type)
    }

    @Test
    fun rejectsTruncatedPacket() {
        assertNull(AapPacket.parse(byteArrayOf(0x04, 0x00, 0x04)))
        assertNull(AapPacket.parse(byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09)))
        assertNull(AapPacket.parse(byteArrayOf(0x01, 0x00, 0x04, 0x00, 0x00, 0x00)))
    }
}
