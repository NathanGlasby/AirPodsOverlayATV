package dev.nathan.airpodstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconGateTest {

    private val validIrk = RpaVerifier.fromHex("7904651EE2CCD926F26E20EE3ECCDE79")
    private val invalidIrk = RpaVerifier.fromHex("7904651EE2CCD926F26E20EE3ECCDEAA")

    private fun beacon(
        model: Int = 0x1B20,
        address: String = "5A:16:2B:91:D1:CD",
        rssi: Int = -45,
    ) = BeaconParser.Beacon(
        address = address,
        rssi = rssi,
        model = model,
        lidState = BeaconParser.LidState.OPEN,
        podsInCase = true,
        leftBattery = 80,
        rightBattery = 80,
        caseBattery = 70,
        leftCharging = false,
        rightCharging = false,
        caseCharging = false,
    )

    private fun evaluate(
        beacon: BeaconParser.Beacon = beacon(),
        modelFilter: Boolean = false,
        identityFilter: Boolean = false,
        irk: ByteArray? = null,
        identityKeyVerified: Boolean = false,
        rssiThreshold: Int = -60,
    ) = BeaconGate.evaluate(
        beacon = beacon,
        modelFilter = modelFilter,
        requiredModel = 0x1B20,
        identityFilter = identityFilter,
        irk = irk,
        identityKeyVerified = identityKeyVerified,
        rssiThreshold = rssiThreshold,
    )

    @Test
    fun normalModeUsesDistance() {
        assertTrue(evaluate().passes)
        assertFalse(evaluate(beacon = beacon(rssi = -80)).passes)
    }

    @Test
    fun strictModelFilterRejectsOtherModels() {
        val result = evaluate(beacon = beacon(model = 0x1420), modelFilter = true)
        assertFalse(result.passes)
        assertEquals(BeaconGate.Reason.WRONG_MODEL, result.reason)
    }

    @Test
    fun strictIdentityRejectsUntilKeyIsVerified() {
        val result = evaluate(
            identityFilter = true,
            irk = invalidIrk,
            identityKeyVerified = false,
        )
        assertFalse(result.passes)
        assertEquals(BeaconGate.Reason.IDENTITY_NOT_READY, result.reason)
    }

    @Test
    fun strictIdentityNeverFallsBackWhenKeyIsMissing() {
        val result = evaluate(identityFilter = true, irk = null)

        assertFalse(result.passes)
        assertEquals(BeaconGate.Reason.IDENTITY_NOT_READY, result.reason)
    }

    @Test
    fun verifiedIdentityKeyRejectsNonMatchingBeacon() {
        val result = evaluate(
            identityFilter = true,
            irk = invalidIrk,
            identityKeyVerified = true,
        )
        assertFalse(result.passes)
        assertEquals(BeaconGate.Reason.IDENTITY_MISMATCH, result.reason)
    }

    @Test
    fun matchingIdentityCanPassBeyondDistanceThreshold() {
        val result = evaluate(
            beacon = beacon(rssi = -90),
            identityFilter = true,
            irk = validIrk,
            identityKeyVerified = true,
        )
        assertTrue(result.passes)
        assertTrue(result.identityMatched)
        assertEquals(BeaconGate.Reason.IDENTITY_MATCH, result.reason)
    }
}
