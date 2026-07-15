package dev.nathan.airpodstv

/** A single decoded AAP packet. */
internal sealed class AapPacket(val raw: ByteArray) {
    class ConnectResponse(
        raw: ByteArray,
        val service: Int,
        val status: Int,
    ) : AapPacket(raw)

    class Disconnect(
        raw: ByteArray,
        val service: Int,
        val status: Int,
    ) : AapPacket(raw)

    class Message(
        raw: ByteArray,
        val command: Int,
        val payload: ByteArray,
    ) : AapPacket(raw)

    class Other(raw: ByteArray, val type: Int) : AapPacket(raw)

    companion object {
        private const val TYPE_CONNECT_RESPONSE = 0x0001
        private const val TYPE_DISCONNECT = 0x0002
        private const val TYPE_MESSAGE = 0x0004

        fun parse(raw: ByteArray): AapPacket? {
            if (raw.size < 4) return null
            val copy = raw.copyOf()
            val type = readLe16(copy, 0)
            val service = readLe16(copy, 2)
            return when (type) {
                TYPE_CONNECT_RESPONSE -> {
                    // status(2), protocol version(4), feature mask(8)
                    if (copy.size < 18) null
                    else ConnectResponse(copy, service, readLe16(copy, 4))
                }

                TYPE_DISCONNECT -> {
                    if (copy.size < 6) null
                    else Disconnect(copy, service, readLe16(copy, 4))
                }

                TYPE_MESSAGE -> {
                    if (copy.size < 6) null
                    else Message(copy, readLe16(copy, 4), copy.copyOfRange(6, copy.size))
                }

                else -> Other(copy, type)
            }
        }

        private fun readLe16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
