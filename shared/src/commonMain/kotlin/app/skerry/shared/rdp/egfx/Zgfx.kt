package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader

/**
 * RDP 8.0 bulk decompression (MS-RDPEGFX 3.1.9.1) — the wrapper every graphics-pipeline message
 * arrives in.
 *
 * It is LZ77 with a static Huffman code: a token either carries a literal byte or a distance back
 * into a 2.5 MB history of everything decompressed so far. The history is what makes an instance
 * stateful and per-channel: a match may reach back into a message decoded minutes ago, so one
 * decompressor belongs to one channel for its lifetime, and feeding it messages out of order or
 * sharing it between channels silently corrupts later frames.
 */
class Zgfx {

    private val history = ByteArray(HISTORY_SIZE)
    private var historyIndex = 0

    /**
     * Decompress one channel message.
     *
     * @throws RdpProtocolException the framing is not one of the two segment descriptors, a segment
     * runs past the message, or the message claims more output than a graphics PDU can hold
     */
    fun decompress(data: ByteArray): ByteArray {
        val reader = RdpReader(data)
        return when (val descriptor = reader.u8()) {
            SEGMENTED_SINGLE -> segment(reader.rest())
            SEGMENTED_MULTIPART -> multipart(reader)
            else -> throw RdpProtocolException("bulk data descriptor 0x${descriptor.toString(16)}")
        }
    }

    /**
     * Several segments concatenated, each decompressed against the shared history. The declared
     * total is only a hint for sizing — what the segments actually produce is what is returned, so
     * a lying header cannot make the client allocate on its word alone.
     */
    private fun multipart(reader: RdpReader): ByteArray {
        val segmentCount = reader.u16le()
        val uncompressedSize = reader.u32le()
        if (uncompressedSize < 0 || uncompressedSize > MAX_MESSAGE_SIZE) {
            throw RdpProtocolException("a bulk message of $uncompressedSize bytes")
        }
        val parts = ArrayList<ByteArray>(segmentCount)
        var total = 0
        repeat(segmentCount) {
            val size = reader.u32le()
            if (size < 0 || size > reader.remaining) {
                throw RdpProtocolException("a bulk segment of $size bytes, ${reader.remaining} remain")
            }
            val part = segment(reader.bytes(size))
            total += part.size
            if (total > MAX_MESSAGE_SIZE) throw RdpProtocolException("bulk segments exceed $MAX_MESSAGE_SIZE bytes")
            parts += part
        }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /** One segment: a flags byte, then either raw bytes or the Huffman-coded bit stream. */
    private fun segment(data: ByteArray): ByteArray {
        if (data.isEmpty()) throw RdpProtocolException("an empty bulk segment")
        val flags = data[0].toInt() and 0xFF
        val body = data.copyOfRange(1, data.size)
        if (flags and PACKET_COMPRESSED == 0) {
            writeHistory(body, 0, body.size)
            return body
        }
        return decompressSegment(body)
    }

    private fun decompressSegment(data: ByteArray): ByteArray {
        // The last byte is not data: it says how many bits of the byte before it are padding.
        if (data.isEmpty()) throw RdpProtocolException("a compressed bulk segment with no data")
        val bits = BulkBitReader(data)
        var out = ByteArray(INITIAL_OUTPUT_SIZE)
        var count = 0

        fun emit(value: Byte) {
            if (count == out.size) {
                if (out.size >= MAX_MESSAGE_SIZE) {
                    throw RdpProtocolException("a bulk segment past $MAX_MESSAGE_SIZE bytes")
                }
                out = out.copyOf(minOf(out.size * 2, MAX_MESSAGE_SIZE))
            }
            out[count++] = value
            history[historyIndex] = value
            historyIndex = (historyIndex + 1) % HISTORY_SIZE
        }

        while (bits.remaining > 0) {
            val token = readToken(bits) ?: break
            if (token.literal) {
                emit((token.valueBase + bits.read(token.valueBits)).toByte())
                continue
            }
            val distance = token.valueBase + bits.read(token.valueBits)
            if (distance == 0) {
                // A distance of zero introduces a run of bytes that resisted compression: the rest
                // of the current byte is padding and the run follows whole.
                val length = bits.read(15)
                bits.alignToByte()
                val raw = bits.readRaw(length)
                for (byte in raw) emit(byte)
                continue
            }
            if (distance > HISTORY_SIZE) throw RdpProtocolException("a bulk match $distance bytes back")
            var source = (historyIndex + HISTORY_SIZE - distance) % HISTORY_SIZE
            // Matches may overlap what they are still writing, so the copy runs byte by byte.
            repeat(matchLength(bits)) {
                emit(history[source])
                source = (source + 1) % HISTORY_SIZE
            }
        }
        return if (count == out.size) out else out.copyOf(count)
    }

    /**
     * A match length: three by default, and otherwise a run of one bits saying which power of two
     * the length starts from, followed by the offset into that range.
     */
    private fun matchLength(bits: BulkBitReader): Int {
        if (bits.read(1) == 0) return 3
        var length = 4
        var extra = 2
        while (bits.read(1) == 1) {
            length *= 2
            extra++
            if (extra > MAX_LENGTH_BITS) throw RdpProtocolException("a bulk match length past $MAX_LENGTH_BITS bits")
        }
        return length + bits.read(extra)
    }

    /** Read bits until they spell one of the tokens; null when the stream ran out first. */
    private fun readToken(bits: BulkBitReader): Token? {
        var prefix = 0
        var length = 0
        for (token in TOKENS) {
            while (length < token.prefixLength) {
                if (bits.remaining <= 0) return null
                prefix = (prefix shl 1) or bits.read(1)
                length++
            }
            if (prefix == token.prefixCode) return token
        }
        return null
    }

    private fun writeHistory(data: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            history[historyIndex] = data[index]
            historyIndex = (historyIndex + 1) % HISTORY_SIZE
        }
    }

    /**
     * A token of the static Huffman code (MS-RDPEGFX 3.1.9.1.2). [valueBase] is added to the
     * [valueBits] that follow the prefix — for a literal that gives the byte, for a match the
     * distance back into the history.
     */
    private class Token(
        val prefixLength: Int,
        val prefixCode: Int,
        val valueBits: Int,
        val literal: Boolean,
        val valueBase: Int,
    )

    /**
     * MSB-first bit reader over a compressed segment. The final byte of the segment holds the count
     * of padding bits in the byte before it and is never itself decoded, so the bit budget is
     * known up front — which is how the decoder knows to stop instead of decoding the padding.
     */
    private class BulkBitReader(private val data: ByteArray) {
        private var position = 0
        private var accumulator = 0L
        private var accumulatorBits = 0

        /** Bits of real data left. */
        var remaining: Int = 8 * (data.size - 1) - (data.last().toInt() and 0xFF)
            private set

        init {
            if (remaining < 0) throw RdpProtocolException("a compressed bulk segment with a bad padding count")
        }

        fun read(count: Int): Int {
            require(count in 0..24) { "bulk reads are at most 24 bits" }
            while (accumulatorBits < count) {
                accumulator = accumulator shl 8
                if (position < data.size - 1) accumulator += (data[position++].toInt() and 0xFF).toLong()
                accumulatorBits += 8
            }
            remaining -= count
            accumulatorBits -= count
            val value = (accumulator ushr accumulatorBits).toInt()
            accumulator = accumulator and ((1L shl accumulatorBits) - 1)
            return value
        }

        /** Drop the bits buffered from the current byte; an unencoded run starts on a byte edge. */
        fun alignToByte() {
            remaining -= accumulatorBits
            accumulatorBits = 0
            accumulator = 0
        }

        fun readRaw(count: Int): ByteArray {
            if (count < 0 || position + count > data.size - 1) {
                throw RdpProtocolException("an unencoded bulk run of $count bytes runs past the segment")
            }
            val out = data.copyOfRange(position, position + count)
            position += count
            remaining -= 8 * count
            return out
        }
    }

    private companion object {
        const val SEGMENTED_SINGLE = 0xE0
        const val SEGMENTED_MULTIPART = 0xE1
        const val PACKET_COMPRESSED = 0x20

        /** The history the encoder assumes; matches are relative to it, so the size is not ours to pick. */
        const val HISTORY_SIZE = 2_500_000

        /** A single graphics message never approaches this; past it the stream is lying. */
        const val MAX_MESSAGE_SIZE = 64 * 1024 * 1024

        const val INITIAL_OUTPUT_SIZE = 65536
        const val MAX_LENGTH_BITS = 24

        /** The static token table (MS-RDPEGFX 3.1.9.1.2), shortest prefix first. */
        val TOKENS = arrayOf(
            Token(1, 0, 8, literal = true, valueBase = 0),
            Token(5, 17, 5, literal = false, valueBase = 0),
            Token(5, 18, 7, literal = false, valueBase = 32),
            Token(5, 19, 9, literal = false, valueBase = 160),
            Token(5, 20, 10, literal = false, valueBase = 672),
            Token(5, 21, 12, literal = false, valueBase = 1696),
            Token(5, 24, 0, literal = true, valueBase = 0x00),
            Token(5, 25, 0, literal = true, valueBase = 0x01),
            Token(6, 44, 14, literal = false, valueBase = 5792),
            Token(6, 45, 15, literal = false, valueBase = 22176),
            Token(6, 52, 0, literal = true, valueBase = 0x02),
            Token(6, 53, 0, literal = true, valueBase = 0x03),
            Token(6, 54, 0, literal = true, valueBase = 0xFF),
            Token(7, 92, 18, literal = false, valueBase = 54944),
            Token(7, 93, 20, literal = false, valueBase = 317088),
            Token(7, 110, 0, literal = true, valueBase = 0x04),
            Token(7, 111, 0, literal = true, valueBase = 0x05),
            Token(7, 112, 0, literal = true, valueBase = 0x06),
            Token(7, 113, 0, literal = true, valueBase = 0x07),
            Token(7, 114, 0, literal = true, valueBase = 0x08),
            Token(7, 115, 0, literal = true, valueBase = 0x09),
            Token(7, 116, 0, literal = true, valueBase = 0x0A),
            Token(7, 117, 0, literal = true, valueBase = 0x0B),
            Token(7, 118, 0, literal = true, valueBase = 0x3A),
            Token(7, 119, 0, literal = true, valueBase = 0x3B),
            Token(7, 120, 0, literal = true, valueBase = 0x3C),
            Token(7, 121, 0, literal = true, valueBase = 0x3D),
            Token(7, 122, 0, literal = true, valueBase = 0x3E),
            Token(7, 123, 0, literal = true, valueBase = 0x3F),
            Token(7, 124, 0, literal = true, valueBase = 0x40),
            Token(7, 125, 0, literal = true, valueBase = 0x80),
            Token(8, 188, 20, literal = false, valueBase = 1365664),
            Token(8, 189, 21, literal = false, valueBase = 2414240),
            Token(8, 252, 0, literal = true, valueBase = 0x0C),
            Token(8, 253, 0, literal = true, valueBase = 0x38),
            Token(8, 254, 0, literal = true, valueBase = 0x39),
            Token(8, 255, 0, literal = true, valueBase = 0x66),
            Token(9, 380, 22, literal = false, valueBase = 4511392),
            Token(9, 381, 23, literal = false, valueBase = 8705696),
            Token(9, 382, 24, literal = false, valueBase = 17094304),
        )
    }
}
