package dev.nathan.airpodstv

import android.bluetooth.le.ScanResult

/**
 * Parses Apple "proximity pairing" BLE advertisements (manufacturer 0x004C, type 0x07),
 * which AirPods broadcast while the case lid is open or the pods are out of the case.
 *
 * Byte layout of the manufacturer-specific data (company ID already stripped by Android):
 *   [0] message type (0x07)      [1] payload length (0x19 = 25)
 *   [2] prefix                   [3..4] device model (big endian)
 *   [5] status                   [6] pod batteries (nibbles)
 *   [7] flags (hi) / case battery (lo)
 *   [8] lid state                [9] color   [10] 0x00   [11..26] encrypted
 */
object BeaconParser {

    const val APPLE_COMPANY_ID = 0x004C

    enum class LidState { OPEN, CLOSED, UNKNOWN }

    data class Beacon(
        val address: String,
        val rssi: Int,
        val model: Int,
        val lidState: LidState,
        val podsInCase: Boolean,
        val statusByte: Int = 0,
        val lidByte: Int = 0,
        val leftBattery: Int?,
        val rightBattery: Int?,
        val caseBattery: Int?,
        val leftCharging: Boolean,
        val rightCharging: Boolean,
        val caseCharging: Boolean,
        val leftInEar: Boolean = false,
        val rightInEar: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        val modelName: String get() = MODEL_NAMES[model] ?: "AirPods (0x%04X)".format(model)
    }

    private val MODEL_NAMES = mapOf(
        0x0220 to "AirPods",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)",
        0x0320 to "Powerbeats 3",
        0x0520 to "Beats X",
        0x0620 to "Beats Solo 3",
    )

    fun parse(result: ScanResult): Beacon? {
        val data = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return null
        if (data.size < 11) return null
        if (data[0].toInt() and 0xFF != 0x07) return null

        val model = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lidByte = data[8].toInt() and 0xFF

        // Status bit 5 set = left pod is primary; unset = values are flipped.
        val flipped = (status and 0x20) == 0
        val thisPodInCase = (status and 0x40) != 0
        val bothInCase = (status and 0x04) != 0
        val podsInCase = thisPodInCase || bothInCase

        // 15 = unknown; 11..14 are anomalous frames (CAPod logs these as ">100%"),
        // safer to drop them than to show a phantom 100%.
        fun nibbleToPercent(v: Int): Int? = when {
            v in 0..10 -> v * 10
            else -> null
        }

        val upper = (podsBattery shr 4) and 0xF
        val lower = podsBattery and 0xF
        val left = nibbleToPercent(if (flipped) upper else lower)
        val right = nibbleToPercent(if (flipped) lower else upper)

        val flags = (flagsCase shr 4) and 0xF
        val caseBattery = nibbleToPercent(flagsCase and 0xF)
        val leftCharging = (flags and (if (flipped) 0x2 else 0x1)) != 0
        val rightCharging = (flags and (if (flipped) 0x1 else 0x2)) != 0
        val caseCharging = (flags and 0x4) != 0

        // In-ear bits swap depending on flip AND on whether the broadcaster is in the case
        // (CAPod: areValuesFlipped xor isThisPodInThecase).
        val earFlip = flipped xor thisPodInCase
        val leftInEar = (status and (if (earFlip) 0x08 else 0x02)) != 0
        val rightInEar = (status and (if (earFlip) 0x02 else 0x08)) != 0

        // Lid bit is only meaningful while the broadcasting pod is in the case.
        val lidState = when {
            !podsInCase -> LidState.UNKNOWN
            (lidByte shr 3) and 1 == 0 -> LidState.OPEN
            else -> LidState.CLOSED
        }

        return Beacon(
            address = result.device?.address ?: "?",
            rssi = result.rssi,
            model = model,
            lidState = lidState,
            podsInCase = podsInCase,
            statusByte = status,
            lidByte = lidByte,
            leftBattery = left,
            rightBattery = right,
            caseBattery = caseBattery,
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            leftInEar = leftInEar,
            rightInEar = rightInEar,
        )
    }
}
