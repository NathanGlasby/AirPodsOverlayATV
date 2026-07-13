package dev.nathan.airpodstv

/**
 * Reassembles AAP packets from the byte-stream exposed by [android.bluetooth.BluetoothSocket].
 * Bluetooth reads are not packet boundaries: one read may contain half a packet or several.
 */
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
            if (buffered.size < HEADER.size + 2) break

            val nextHeaderAt = findHeader(buffered, HEADER.size)
            val knownLength = knownPacketLength(buffered)
            val packetLength = when {
                knownLength != null && buffered.size >= knownLength -> knownLength
                knownCommand(buffered) -> break
                nextHeaderAt > 0 -> nextHeaderAt
                else -> break
            }
            packets += buffered.copyOfRange(0, packetLength)
            buffered = buffered.copyOfRange(packetLength, buffered.size)
        }

        if (buffered.size > maxBufferSize) {
            buffered = buffered.takeLast(HEADER.size - 1).toByteArray()
        }
        return packets
    }

    private fun knownPacketLength(data: ByteArray): Int? {
        if (data.size < 6) return null
        val command = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        return when (command) {
            0x04 -> if (data.size >= 7) 7 + (data[6].toInt() and 0xFF) * 5 else null
            0x06, 0x09 -> 8
            0x31 -> keyPacketLength(data)
            else -> null
        }
    }

    private fun knownCommand(data: ByteArray): Boolean {
        if (data.size < 6) return false
        val command = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        return command == 0x04 || command == 0x06 || command == 0x09 || command == 0x31
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
        for (i in from..data.size - HEADER.size) {
            if (HEADER.indices.all { data[i + it] == HEADER[it] }) return i
        }
        return -1
    }

    private fun partialHeaderSuffixLength(data: ByteArray): Int {
        val max = minOf(data.size, HEADER.size - 1)
        for (length in max downTo 1) {
            if ((0 until length).all { data[data.size - length + it] == HEADER[it] }) return length
        }
        return 0
    }

    private companion object {
        val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)
    }
}
