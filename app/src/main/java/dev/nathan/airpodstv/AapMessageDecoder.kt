package dev.nathan.airpodstv

internal object AapMessageDecoder {
    sealed interface Update {
        data class Batteries(
            val values: Map<AapClient.Component, AapClient.Battery>,
        ) : Update

        data class EarPlacement(
            val primary: AapClient.Placement,
            val secondary: AapClient.Placement,
        ) : Update

        data class AncMode(val wireMode: Int) : Update
        data class IdentityKey(val value: ByteArray) : Update
    }

    fun usefulUpdate(packet: AapPacket): Update? = when (packet) {
        is AapPacket.Message -> decode(packet.command, packet.payload)
        else -> null
    }

    fun decode(command: Int, payload: ByteArray): Update? = when (command) {
        0x04 -> decodeBattery(payload)
        0x06 -> decodeEarPlacement(payload)
        0x09 -> decodeControl(payload)
        0x31 -> decodeIdentityKey(payload)
        else -> null
    }

    private fun decodeBattery(payload: ByteArray): Update.Batteries? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        if (count == 0 || payload.size < 1 + count * 5) return null

        val values = mutableMapOf<AapClient.Component, AapClient.Battery>()
        var offset = 1
        repeat(count) {
            val type = payload[offset].toInt() and 0xFF
            val percent = payload[offset + 2].toInt() and 0xFF
            val status = payload[offset + 3].toInt() and 0xFF
            offset += 5

            val component = when (type) {
                0x04 -> AapClient.Component.LEFT
                0x02 -> AapClient.Component.RIGHT
                0x08 -> AapClient.Component.CASE
                else -> null
            }
            if (component != null && percent <= 100) {
                values[component] = AapClient.Battery(percent, charging = status == 0x01)
            }
        }
        return values.takeIf { it.isNotEmpty() }?.let { Update.Batteries(it) }
    }

    private fun decodeEarPlacement(payload: ByteArray): Update.EarPlacement? {
        if (payload.size < 2) return null
        val primary = decodePlacement(payload[0].toInt() and 0xFF) ?: return null
        val secondary = decodePlacement(payload[1].toInt() and 0xFF) ?: return null
        return Update.EarPlacement(primary, secondary)
    }

    private fun decodePlacement(value: Int): AapClient.Placement? = when (value) {
        0x00 -> AapClient.Placement.IN_EAR
        0x01 -> AapClient.Placement.OUT_OF_EAR
        0x02 -> AapClient.Placement.IN_CASE
        else -> null
    }

    private fun decodeControl(payload: ByteArray): Update.AncMode? {
        if (payload.size < 2) return null
        val settingId = payload[0].toInt() and 0xFF
        val value = payload[1].toInt() and 0xFF
        if (settingId != 0x0D || value !in AapClient.ANC_OFF..AapClient.ANC_ADAPTIVE) return null
        return Update.AncMode(value)
    }

    private fun decodeIdentityKey(payload: ByteArray): Update.IdentityKey? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        var offset = 1
        repeat(count) {
            if (offset + 4 > payload.size) return null
            val keyType = payload[offset].toInt() and 0xFF
            val keyLength = payload[offset + 2].toInt() and 0xFF
            offset += 4
            if (offset + keyLength > payload.size) return null
            val value = payload.copyOfRange(offset, offset + keyLength)
            offset += keyLength
            if (keyType == 0x01 && keyLength == 16) return Update.IdentityKey(value)
        }
        return null
    }
}
