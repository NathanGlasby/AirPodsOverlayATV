package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapDeviceStateTest {

    private val left = AapClient.Component.LEFT
    private val right = AapClient.Component.RIGHT
    private val case = AapClient.Component.CASE

    @Test
    fun firstBatteryPacketEstablishesBaselineWithoutAnnouncement() {
        val state = AapDeviceState()
        state.beginSession(ADDRESS, 1L)

        val result = state.applyBattery(
            mapOf(
                left to AapClient.Battery(80, charging = false),
                right to AapClient.Battery(75, charging = true),
            )
        )

        assertTrue(result.changed)
        assertEquals("L 80% \u00B7 R 75%\u26A1", result.batteryLine)
        assertNull(result.announcement)
    }

    @Test
    fun laterBatteryDeltaAnnouncesTheCompleteCurrentState() {
        val state = AapDeviceState()
        state.applyBattery(
            mapOf(
                left to AapClient.Battery(80, false),
                right to AapClient.Battery(75, false),
                case to AapClient.Battery(60, false),
            )
        )

        val result = state.applyBattery(mapOf(left to AapClient.Battery(79, false)))

        assertTrue(result.changed)
        assertEquals("L 79% \u00B7 R 75% \u00B7 Case 60%", result.batteryLine)
        assertEquals(result.batteryLine, result.announcement)
    }

    @Test
    fun unavailableBatteryComponentIsRemovedWithoutClearingOthers() {
        val state = AapDeviceState()
        state.applyBattery(
            mapOf(
                left to AapClient.Battery(80, false),
                right to AapClient.Battery(75, false),
                case to AapClient.Battery(60, false),
            )
        )

        val result = state.applyBattery(mapOf(right to null))

        assertTrue(result.changed)
        assertEquals("L 80% \u00B7 Case 60%", result.batteryLine)
        assertFalse(state.snapshot().batteries.containsKey(right))
        assertEquals(result.batteryLine, result.announcement)
    }

    @Test
    fun removingTheLastBatteryProducesOneUnavailableAnnouncement() {
        val state = AapDeviceState()
        state.applyBattery(mapOf(left to AapClient.Battery(80, false)))

        val removal = state.applyBattery(mapOf(left to null))
        val duplicate = state.applyBattery(mapOf(left to null))

        assertNull(removal.batteryLine)
        assertEquals("Battery unavailable", removal.announcement)
        assertFalse(duplicate.changed)
        assertNull(duplicate.announcement)
    }

    @Test
    fun duplicateBatteryAndAncUpdatesAreSuppressed() {
        val state = AapDeviceState()
        val battery = AapClient.Battery(80, false)
        state.applyBattery(mapOf(left to battery))

        val duplicate = state.applyBattery(mapOf(left to battery))

        assertFalse(duplicate.changed)
        assertNull(duplicate.announcement)
        assertTrue(state.applyAncMode(AapClient.ANC_ON))
        assertFalse(state.applyAncMode(AapClient.ANC_ON))
    }

    @Test
    fun partialEarPlacementKeepsTheMissingPodUnknown() {
        val state = AapDeviceState()

        val result = state.applyEarPlacement(
            source = AapDeviceState.EarSource.AAP,
            primary = AapClient.Placement.IN_EAR,
            secondary = null,
        )

        assertTrue(result.changed)
        assertEquals(1, result.knownPlacementCount)
        assertEquals(1, result.inEarCount)
        assertEquals(AapClient.Placement.IN_EAR, state.snapshot().primaryPlacement)
        assertNull(state.snapshot().secondaryPlacement)
        assertFalse(result.bothPodsInCase)
    }

    @Test
    fun partialUpdateCanClearAPreviouslyKnownSide() {
        val state = AapDeviceState()
        state.applyEarPlacement(
            AapDeviceState.EarSource.AAP,
            AapClient.Placement.IN_EAR,
            AapClient.Placement.OUT_OF_EAR,
        )

        val result = state.applyEarPlacement(
            AapDeviceState.EarSource.AAP,
            AapClient.Placement.OUT_OF_EAR,
            null,
        )

        assertTrue(result.knownSetChanged)
        assertEquals(1, result.knownPlacementCount)
        assertEquals(0, result.inEarCount)
        assertNull(state.snapshot().secondaryPlacement)
    }

    @Test
    fun sourceHandoffDoesNotBorrowTheMissingPod() {
        val state = AapDeviceState()
        state.applyEarPlacement(
            AapDeviceState.EarSource.BLE,
            AapClient.Placement.IN_EAR,
            AapClient.Placement.IN_CASE,
        )

        val result = state.applyEarPlacement(
            AapDeviceState.EarSource.AAP,
            AapClient.Placement.OUT_OF_EAR,
            null,
        )

        assertTrue(result.sourceChanged)
        assertTrue(result.knownSetChanged)
        assertEquals(AapDeviceState.EarSource.AAP, state.snapshot().earSource)
        assertEquals(AapClient.Placement.OUT_OF_EAR, state.snapshot().primaryPlacement)
        assertNull(state.snapshot().secondaryPlacement)
    }

    @Test
    fun duplicateEarPlacementIsReportedWithoutChangingState() {
        val state = AapDeviceState()
        state.applyEarPlacement(
            AapDeviceState.EarSource.AAP,
            AapClient.Placement.IN_EAR,
            null,
        )

        val duplicate = state.applyEarPlacement(
            AapDeviceState.EarSource.AAP,
            AapClient.Placement.IN_EAR,
            null,
        )

        assertFalse(duplicate.changed)
        assertFalse(duplicate.sourceChanged)
        assertFalse(duplicate.knownSetChanged)
        assertEquals(1, duplicate.inEarCount)
    }

    @Test
    fun generationAndAddressChangesResetEveryMeasurementAndBaseline() {
        val state = AapDeviceState()
        assertTrue(state.beginSession(ADDRESS, 1L))
        state.applyBattery(mapOf(left to AapClient.Battery(80, false)))
        state.applyEarPlacement(
            AapDeviceState.EarSource.AAP,
            AapClient.Placement.IN_EAR,
            AapClient.Placement.IN_CASE,
        )
        state.applyAncMode(AapClient.ANC_ADAPTIVE)
        assertFalse(state.beginSession(ADDRESS, 1L))
        assertEquals(80, state.snapshot().batteries[left]?.percent)

        assertTrue(state.beginSession(ADDRESS, 2L))
        assertEmptyMeasurements(state.snapshot())
        val nextGenerationBaseline = state.applyBattery(
            mapOf(left to AapClient.Battery(79, false))
        )
        assertNull(nextGenerationBaseline.announcement)

        assertTrue(state.beginSession(OTHER_ADDRESS, 2L))
        assertEmptyMeasurements(state.snapshot())
        assertEquals(AapDeviceState.SessionKey(OTHER_ADDRESS, 2L), state.snapshot().sessionKey)
    }

    @Test
    fun resetSessionClearsTheSessionAndMakesTheNextBatteryABaseline() {
        val state = AapDeviceState()
        state.beginSession(ADDRESS, 1L)
        state.applyBattery(mapOf(left to AapClient.Battery(80, false)))
        state.applyBattery(mapOf(left to AapClient.Battery(79, false)))

        assertTrue(state.resetSession())
        assertEmptyMeasurements(state.snapshot())
        assertNull(state.snapshot().sessionKey)
        assertNull(
            state.applyBattery(mapOf(left to AapClient.Battery(78, false))).announcement
        )
    }

    @Test
    fun batteryDecoderPreservesUnavailableComponentsAsRemovals() {
        val payload = byteArrayOf(
            0x03,
            0x04, 0x00, 0x64, 0x01, 0x00,
            0x02, 0x00, 0xFF.toByte(), 0x00, 0x00,
            0x08, 0x00, 0x7F, 0x00, 0x00,
        )

        val decoded = AapClient.decodeBatteryPayload(payload)

        assertEquals(AapClient.Battery(100, true), decoded[left])
        assertTrue(decoded.containsKey(right))
        assertNull(decoded[right])
        assertTrue(decoded.containsKey(case))
        assertNull(decoded[case])
    }

    private fun assertEmptyMeasurements(snapshot: AapDeviceState.Snapshot) {
        assertTrue(snapshot.batteries.isEmpty())
        assertNull(snapshot.primaryPlacement)
        assertNull(snapshot.secondaryPlacement)
        assertNull(snapshot.earSource)
        assertNull(snapshot.ancMode)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val OTHER_ADDRESS = "11:22:33:44:55:66"
    }
}
