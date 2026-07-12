package dev.nathan.airpodstv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpaVerifierTest {

    private val address = "5A:16:2B:91:D1:CD"
    private val irk = RpaVerifier.fromHex("7904651EE2CCD926F26E20EE3ECCDE79")

    @Test
    fun acceptsKnownCapodVectorInEitherKeyByteOrder() {
        assertTrue(RpaVerifier.verify(address, irk))
        assertTrue(RpaVerifier.verify(address, irk.reversedArray()))
    }

    @Test
    fun rejectsWrongKey() {
        val wrong = RpaVerifier.fromHex("7904651EE2CCD926F26E20EE3ECCDEAA")
        assertFalse(RpaVerifier.verify(address, wrong))
    }
}
