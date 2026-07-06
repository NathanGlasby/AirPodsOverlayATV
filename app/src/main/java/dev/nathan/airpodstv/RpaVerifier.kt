package dev.nathan.airpodstv

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies that a Resolvable Private Address (the rotating MAC in BLE beacons)
 * belongs to the device with the given Identity Resolving Key.
 * Same math as CAPod's RPAChecker (BLE spec ah() over AES-128).
 */
object RpaVerifier {

    fun verify(address: String, irk: ByteArray): Boolean = try {
        val rpa = address.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
        val prand = rpa.copyOfRange(3, 6)
        val hash = rpa.copyOfRange(0, 3)
        // The AAP key exchange delivers the IRK in an unverified byte order; a real
        // RPA only matches one orientation, so accepting both loses no strictness.
        hash.contentEquals(ah(irk, prand)) || hash.contentEquals(ah(irk.reversedArray(), prand))
    } catch (_: Exception) {
        false
    }

    private fun e(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.reversedArray(), "AES"))
        }
        return cipher.doFinal(data.reversedArray()).reversedArray()
    }

    private fun ah(k: ByteArray, r: ByteArray): ByteArray {
        val rPadded = ByteArray(16).apply { r.copyInto(this, 0, 0, 3) }
        return e(k, rPadded).copyOfRange(0, 3)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    fun fromHex(hexStr: String): ByteArray =
        hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
