package app.skerry.shared.rdp

/**
 * The slice of aligned BASIC-PER (X.691) that T.124 GCC and the T.125 domain PDUs use. Like [Ber],
 * only the handful of productions that appear on the RDP wire are implemented, and every one of them
 * is pinned by the annotated dumps in MS-RDPBCGR section 4.1.
 */
object Per {
    /** MCS user and channel ids are encoded relative to this minimum (T.125 UserId ::= 1001..65535). */
    const val USER_ID_BASE = 1001

    /** Length determinant: one byte below 128, otherwise two with the high bit set. */
    fun length(writer: RdpWriter, value: Int) {
        if (value < 0x80) writer.u8(value) else writer.u16be(value or 0x8000)
    }

    fun readLength(reader: RdpReader): Int {
        val first = reader.u8()
        if (first and 0x80 == 0) return first
        // 0xC0 marks a fragmented length, which nothing in the RDP connection sequence uses.
        if (first and 0x40 != 0) throw RdpProtocolException("fragmented PER length is not supported")
        return ((first and 0x7F) shl 8) or reader.u8()
    }

    fun choice(writer: RdpWriter, choice: Int) {
        writer.u8(choice)
    }

    fun readChoice(reader: RdpReader): Int = reader.u8()

    fun selection(writer: RdpWriter, selection: Int) {
        writer.u8(selection)
    }

    /** [count] zero bytes of PER padding (the encoding realigns on byte boundaries). */
    fun padding(writer: RdpWriter, count: Int) {
        writer.zeros(count)
    }

    fun numberOfSets(writer: RdpWriter, count: Int) {
        writer.u8(count)
    }

    /**
     * NumericString constrained to [minLength]: the length determinant counts from that minimum and
     * each digit occupies four bits (`"1"` becomes the single byte 0x10).
     */
    fun numericString(writer: RdpWriter, text: String, minLength: Int) {
        length(writer, text.length - minLength)
        var index = 0
        while (index < text.length) {
            val high = digit(text[index])
            val low = if (index + 1 < text.length) digit(text[index + 1]) else 0
            writer.u8((high shl 4) or low)
            index += 2
        }
    }

    /**
     * OCTET STRING constrained to [minLength]: the determinant is the length above that minimum,
     * so a fixed-size field (the four-byte H.221 key) is written as a plain zero.
     */
    fun octetString(writer: RdpWriter, data: ByteArray, minLength: Int) {
        length(writer, if (data.size >= minLength) data.size - minLength else minLength)
        writer.bytes(data)
    }

    /** OBJECT IDENTIFIER in the packed form T.124 uses: the first two arcs fold into one byte. */
    fun objectIdentifier(writer: RdpWriter, oid: IntArray) {
        require(oid.size == 6) { "only the six-arc T.124 identifier is supported" }
        writer.u8(5) // length of the encoded object
        writer.u8((oid[0] shl 4) or oid[1])
        writer.u8(oid[2])
        writer.u8(oid[3])
        writer.u8(oid[4])
        writer.u8(oid[5])
    }

    /** Read an object identifier and check it is [expected]; the only one RDP uses is T.124. */
    fun readObjectIdentifier(reader: RdpReader, expected: IntArray) {
        val size = reader.u8()
        if (size != 5) throw RdpProtocolException("unexpected object identifier length $size")
        val first = reader.u8()
        val actual = intArrayOf(first shr 4, first and 0x0F, reader.u8(), reader.u8(), reader.u8(), reader.u8())
        if (!actual.contentEquals(expected)) {
            throw RdpProtocolException("unexpected object identifier ${actual.joinToString(".")}")
        }
    }

    /** MCS user/channel id, stored relative to [USER_ID_BASE]. */
    fun userId(writer: RdpWriter, id: Int) {
        val relative = id - USER_ID_BASE
        if (relative !in 0..0xFFFF) throw RdpProtocolException("user id $id out of range")
        writer.u16be(relative)
    }

    fun readUserId(reader: RdpReader): Int = reader.u16be() + USER_ID_BASE

    /** 16-bit integer constrained to a minimum, as channel ids inside domain PDUs are. */
    fun integer16(writer: RdpWriter, value: Int, minimum: Int = 0) {
        writer.u16be(value - minimum)
    }

    fun readInteger16(reader: RdpReader, minimum: Int = 0): Int = reader.u16be() + minimum

    /** Length-prefixed integer as domain PDUs carry (`00`, `01 00`, …). */
    fun integer(writer: RdpWriter, value: Int) {
        when {
            value <= 0xFF -> writer.u8(1).u8(value)
            value <= 0xFFFF -> writer.u8(2).u16be(value)
            else -> writer.u8(4).u32be(value)
        }
    }

    private fun digit(ch: Char): Int {
        if (ch !in '0'..'9') throw RdpProtocolException("non-numeric character in a NumericString")
        return ch - '0'
    }
}
