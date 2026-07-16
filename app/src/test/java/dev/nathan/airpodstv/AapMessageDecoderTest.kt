package dev.nathan.airpodstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapMessageDecoderTest {
    @Test
    fun successfulConnectResponseIsNotUsefulTraffic() {
        val response = AapPacket.ConnectResponse(
            raw = ByteArray(18),
            service = 4,
            status = 0,
        )

        assertNull(AapMessageDecoder.usefulUpdate(response))
    }

    @Test
    fun unknownAndTruncatedMessagesAreNotUseful() {
        assertNull(AapMessageDecoder.decode(0x7F, byteArrayOf(1, 2)))
        assertNull(AapMessageDecoder.decode(0x04, byteArrayOf(1, 0x04, 0)))
        assertNull(AapMessageDecoder.decode(0x06, byteArrayOf(0)))
        assertNull(AapMessageDecoder.decode(0x09, byteArrayOf(0x0D)))
        assertNull(AapMessageDecoder.decode(0x31, byteArrayOf(1, 1, 0, 16)))
    }

    @Test
    fun validBatteryMessageIsUseful() {
        val update = AapMessageDecoder.decode(
            0x04,
            byteArrayOf(1, 0x04, 0, 87, 1, 0),
        ) as AapMessageDecoder.Update.Batteries

        assertEquals(AapClient.Battery(87, charging = true), update.values[AapClient.Component.LEFT])
    }

    @Test
    fun invalidBatteryReadingsDoNotActivateTheSession() {
        assertNull(
            AapMessageDecoder.decode(
                0x04,
                byteArrayOf(1, 0x04, 0, 127, 0, 0),
            )
        )
    }

    @Test
    fun completeEarPlacementIsUseful() {
        val update = AapMessageDecoder.decode(
            0x06,
            byteArrayOf(0, 2),
        ) as AapMessageDecoder.Update.EarPlacement

        assertEquals(AapClient.Placement.IN_EAR, update.primary)
        assertEquals(AapClient.Placement.IN_CASE, update.secondary)
    }

    @Test
    fun oneKnownEarPlacementIsStillUseful() {
        val update = AapMessageDecoder.decode(
            0x06,
            byteArrayOf(0, 9),
        ) as AapMessageDecoder.Update.EarPlacement

        assertEquals(AapClient.Placement.IN_EAR, update.primary)
        assertEquals(AapClient.Placement.UNKNOWN, update.secondary)
        assertNull(AapMessageDecoder.decode(0x06, byteArrayOf(8, 9)))
    }

    @Test
    fun validAncModeIsUseful() {
        val update = AapMessageDecoder.decode(
            0x09,
            byteArrayOf(0x0D, AapClient.ANC_ADAPTIVE.toByte()),
        ) as AapMessageDecoder.Update.AncMode

        assertEquals(AapClient.ANC_ADAPTIVE, update.wireMode)
        assertNull(AapMessageDecoder.decode(0x09, byteArrayOf(0x0D, 9)))
    }

    @Test
    fun sixteenByteIdentityKeyIsUseful() {
        val key = ByteArray(16) { it.toByte() }
        val payload = byteArrayOf(1, 1, 0, 16, 0) + key
        val update = AapMessageDecoder.decode(
            0x31,
            payload,
        ) as AapMessageDecoder.Update.IdentityKey

        assertArrayEquals(key, update.value)
        assertTrue(update.value !== key)
    }
}
