package dev.nathan.airpodstv

/** Reassembles the AAP packet schemas consumed by this app across Bluetooth reads. */
internal class AapPacketFramer(private val maxBufferSize: Int = 16 * 1024) {
    private var buffered = ByteArray(0)

    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffered += chunk
        val packets = mutableListOf<ByteArray>()

        while (true) {
            val headerAt = findHeader(buffered)
            if (headerAt < 0) {
                // Retain a possible partial header at the end and discard unrelated bytes.
                buffered = buffered.takeLast(partialHeaderSuffixLength(buffered)).toByteArray()
                break
            }
            if (headerAt > 0) buffered = buffered.copyOfRange(headerAt, buffered.size)
            if (buffered.size < HEADER_SIZE) break

            val nextHeaderAt = findHeader(buffered, HEADER_SIZE)
            val knownLength = knownFrameLength(buffered)
            val packetLength = when {
                knownLength != null && buffered.size >= knownLength -> knownLength
                hasKnownSchema(buffered) -> break
                nextHeaderAt > 0 -> nextHeaderAt
                else -> break
            }
            packets += buffered.copyOfRange(0, packetLength)
            buffered = buffered.copyOfRange(packetLength, buffered.size)
        }

        if (buffered.size > maxBufferSize) {
            buffered = buffered.takeLast(HEADER_SIZE - 1).toByteArray()
        }
        return packets
    }

    private fun knownFrameLength(data: ByteArray): Int? {
        if (data.size < HEADER_SIZE) return null
        return when (readLe16(data, 0)) {
            TYPE_CONNECT -> 16
            TYPE_CONNECT_RESPONSE -> 18
            TYPE_DISCONNECT -> 6
            TYPE_DISCONNECT_RESPONSE -> 4
            TYPE_MESSAGE -> knownMessageLength(data)
            else -> null
        }
    }

    private fun knownMessageLength(data: ByteArray): Int? {
        if (data.size < 6) return null
        val command = readLe16(data, 4)
        return when (command) {
            0x04 -> if (data.size >= 7) 7 + (data[6].toInt() and 0xFF) * 5 else null
            0x06 -> 8
            0x09 -> 11
            0x31 -> keyPacketLength(data)
            else -> null
        }
    }

    private fun hasKnownSchema(data: ByteArray): Boolean {
        if (data.size < HEADER_SIZE) return false
        return when (readLe16(data, 0)) {
            TYPE_CONNECT, TYPE_CONNECT_RESPONSE, TYPE_DISCONNECT, TYPE_DISCONNECT_RESPONSE -> true
            TYPE_MESSAGE -> data.size >= 6 && readLe16(data, 4) in KNOWN_MESSAGE_COMMANDS
            else -> false
        }
    }

    private fun keyPacketLength(data: ByteArray): Int? {
        if (data.size < 7) return null
        val count = data[6].toInt() and 0xFF
        var offset = 7
        repeat(count) {
            if (data.size < offset + 4) return null
            val keyLength = data[offset + 2].toInt() and 0xFF
            offset += 4 + keyLength
            if (offset > maxBufferSize) return null
        }
        return offset
    }

    private fun findHeader(data: ByteArray, from: Int = 0): Int {
        for (i in from..data.size - HEADER_SIZE) {
            if (HEADERS.any { header -> header.indices.all { data[i + it] == header[it] } }) {
                return i
            }
        }
        return -1
    }

    private fun partialHeaderSuffixLength(data: ByteArray): Int {
        val max = minOf(data.size, HEADER_SIZE - 1)
        for (length in max downTo 1) {
            if (HEADERS.any { header ->
                    (0 until length).all { data[data.size - length + it] == header[it] }
                }
            ) {
                return length
            }
        }
        return 0
    }

    private fun readLe16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val HEADER_SIZE = 4
        const val TYPE_CONNECT = 0x0000
        const val TYPE_CONNECT_RESPONSE = 0x0001
        const val TYPE_DISCONNECT = 0x0002
        const val TYPE_DISCONNECT_RESPONSE = 0x0003
        const val TYPE_MESSAGE = 0x0004
        val KNOWN_MESSAGE_COMMANDS = setOf(0x04, 0x06, 0x09, 0x31)
        val HEADERS = (TYPE_CONNECT..TYPE_MESSAGE).map { type ->
            byteArrayOf(type.toByte(), 0x00, 0x04, 0x00)
        }
    }
}
