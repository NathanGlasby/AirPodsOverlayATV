package dev.nathan.airpodstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AapPacketFramerTest {
    private val earPacket = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x06, 0x00, 0x00, 0x01)
    private val controlPacket = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, 0x02, 0x00, 0x00, 0x00,
    )
    private val connectResponse = byteArrayOf(
        0x01, 0x00, 0x04, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    @Test
    fun reassemblesPacketSplitAcrossReads() {
        val framer = AapPacketFramer()
        assertEquals(emptyList<ByteArray>(), framer.feed(earPacket.copyOfRange(0, 3)))
        val packets = framer.feed(earPacket.copyOfRange(3, earPacket.size))
        assertEquals(1, packets.size)
        assertArrayEquals(earPacket, packets.single())
    }

    @Test
    fun separatesMultiplePacketsInOneRead() {
        val packets = AapPacketFramer().feed(earPacket + controlPacket)
        assertEquals(2, packets.size)
        assertArrayEquals(earPacket, packets[0])
        assertArrayEquals(controlPacket, packets[1])
    }

    @Test
    fun reassemblesConnectResponseSplitAcrossReads() {
        val framer = AapPacketFramer()
        assertEquals(emptyList<ByteArray>(), framer.feed(connectResponse.copyOfRange(0, 7)))

        val packets = framer.feed(connectResponse.copyOfRange(7, connectResponse.size))

        assertEquals(1, packets.size)
        assertArrayEquals(connectResponse, packets.single())
        assertTrue(AapPacket.parse(packets.single()) is AapPacket.ConnectResponse)
    }

    @Test
    fun separatesConnectResponseAndMessageInOneRead() {
        val packets = AapPacketFramer().feed(connectResponse + earPacket)

        assertEquals(2, packets.size)
        assertTrue(AapPacket.parse(packets[0]) is AapPacket.ConnectResponse)
        assertTrue(AapPacket.parse(packets[1]) is AapPacket.Message)
    }

    @Test
    fun waitsForCompleteControlPacket() {
        val framer = AapPacketFramer()
        assertEquals(emptyList<ByteArray>(), framer.feed(controlPacket.copyOfRange(0, 8)))

        val packets = framer.feed(controlPacket.copyOfRange(8, controlPacket.size))

        assertEquals(1, packets.size)
        assertArrayEquals(controlPacket, packets.single())
    }

    @Test
    fun keepsControlAndEarPacketsAligned() {
        val packets = AapPacketFramer().feed(controlPacket + earPacket)

        assertEquals(2, packets.size)
        assertArrayEquals(controlPacket, packets[0])
        assertArrayEquals(earPacket, packets[1])
    }

    @Test
    fun skipsNoiseBeforeHeader() {
        val packets = AapPacketFramer().feed(byteArrayOf(0x55, 0x66) + earPacket)
        assertEquals(1, packets.size)
        assertArrayEquals(earPacket, packets.single())
    }

    @Test
    fun waitsForVariableLengthBatteryPacket() {
        val packet = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
            0x04, 0x00, 0x64, 0x01, 0x00,
        )
        val framer = AapPacketFramer()
        assertEquals(emptyList<ByteArray>(), framer.feed(packet.copyOfRange(0, 9)))
        val packets = framer.feed(packet.copyOfRange(9, packet.size))
        assertArrayEquals(packet, packets.single())
    }

    @Test
    fun doesNotSplitPartialKnownPacketAtHeaderBytesInPayload() {
        val packet = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
            0x04, 0x00, 0x04, 0x00, 0x01,
        )
        val framer = AapPacketFramer()
        assertEquals(emptyList<ByteArray>(), framer.feed(packet.copyOfRange(0, 11)))
        val packets = framer.feed(packet.copyOfRange(11, packet.size))
        assertArrayEquals(packet, packets.single())
    }

    @Test
    fun waitsForVariableLengthIdentityKeyPacket() {
        val irk = ByteArray(16) { it.toByte() }
        val packet = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x31, 0x00, 0x01,
            0x01, 0x00, 0x10, 0x00,
        ) + irk
        val framer = AapPacketFramer()
        assertEquals(emptyList<ByteArray>(), framer.feed(packet.copyOfRange(0, 18)))

        val packets = framer.feed(packet.copyOfRange(18, packet.size))

        assertEquals(1, packets.size)
        assertArrayEquals(packet, packets.single())
    }
}
