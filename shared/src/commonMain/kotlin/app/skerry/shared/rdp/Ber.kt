package app.skerry.shared.rdp

/**
 * The slice of ASN.1 BER that MCS connect PDUs use (T.125): application tags, octet strings,
 * booleans, integers, enumerated values and sequences. Nothing else is needed, so nothing else is
 * implemented — a general BER codec would be larger than the six types on the wire.
 *
 * Integers are written unsigned and minimal, which is what a real client puts on the wire
 * (`maxMCSPDUsize = 65535` is `02 02 ff ff`, not the strictly signed `02 03 00 ff ff`); the reader
 * accepts both forms because servers do send the padded one.
 */
object Ber {
    private const val TAG_BOOLEAN = 0x01
    private const val TAG_INTEGER = 0x02
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_ENUMERATED = 0x0A
    private const val TAG_SEQUENCE = 0x30

    /** Widest integer any MCS field holds; anything longer is a malformed or hostile length. */
    private const val MAX_INTEGER_BYTES = 4

    /** Definite-form length in its shortest encoding: 1, 2 or 3 bytes. */
    fun length(writer: RdpWriter, length: Int) {
        when {
            length < 0x80 -> writer.u8(length)
            length <= 0xFF -> writer.u8(0x81).u8(length)
            else -> writer.u8(0x82).u16be(length)
        }
    }

    /** Bytes [length] occupies once encoded — needed to size a container before filling it. */
    fun lengthSize(length: Int): Int = when {
        length < 0x80 -> 1
        length <= 0xFF -> 2
        else -> 3
    }

    /**
     * Constructed application tag in the multi-octet form MCS uses (`7f 65` = APPLICATION 101 =
     * Connect-Initial), followed by the length of its content.
     */
    fun applicationTag(writer: RdpWriter, tag: Int, length: Int) {
        require(tag > 30) { "single-octet application tags are not used by MCS" }
        writer.u8(0x7F)
        writer.u8(tag)
        length(writer, length)
    }

    fun octetString(data: ByteArray): ByteArray =
        RdpWriter(data.size + 4).also {
            it.u8(TAG_OCTET_STRING)
            length(it, data.size)
            it.bytes(data)
        }.toByteArray()

    fun boolean(value: Boolean): ByteArray =
        byteArrayOf(TAG_BOOLEAN.toByte(), 0x01, if (value) 0xFF.toByte() else 0x00)

    fun integer(value: Int): ByteArray {
        val body = when {
            value and 0xFF.inv() == 0 -> byteArrayOf(value.toByte())
            value and 0xFFFF.inv() == 0 -> byteArrayOf((value ushr 8).toByte(), value.toByte())
            value and 0xFFFFFF.inv() == 0 ->
                byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

            else -> byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
        }
        return RdpWriter(body.size + 2).u8(TAG_INTEGER).u8(body.size).bytes(body).toByteArray()
    }

    /** SEQUENCE wrapper around already-encoded [content]. */
    fun sequence(content: ByteArray): ByteArray =
        RdpWriter(content.size + 4).also {
            it.u8(TAG_SEQUENCE)
            length(it, content.size)
            it.bytes(content)
        }.toByteArray()

    /** Read a definite-form length. Indefinite lengths are not used by MCS and are refused. */
    fun readLength(reader: RdpReader): Int {
        val first = reader.u8()
        if (first and 0x80 == 0) return first
        val count = first and 0x7F
        if (count == 0 || count > 2) throw RdpProtocolException("unsupported BER length form 0x${first.toString(16)}")
        var value = 0
        repeat(count) { value = (value shl 8) or reader.u8() }
        return value
    }

    /** Consume an application tag, checking it is [tag], and return the content length. */
    fun readApplicationTag(reader: RdpReader, tag: Int): Int {
        val first = reader.u8()
        if (first != 0x7F) throw RdpProtocolException("expected a constructed application tag, got 0x${first.toString(16)}")
        val actual = reader.u8()
        if (actual != tag) throw RdpProtocolException("expected APPLICATION $tag, got $actual")
        return readLength(reader)
    }

    fun readInteger(reader: RdpReader): Int = readUnsigned(reader, TAG_INTEGER)

    fun readEnumerated(reader: RdpReader): Int = readUnsigned(reader, TAG_ENUMERATED)

    /** Consume an octet string and return its bytes. */
    fun readOctetString(reader: RdpReader): ByteArray {
        expectTag(reader, TAG_OCTET_STRING)
        return reader.bytes(readLength(reader))
    }

    /** Consume a SEQUENCE header and return a reader bounded to its content. */
    fun readSequence(reader: RdpReader): RdpReader {
        expectTag(reader, TAG_SEQUENCE)
        return reader.slice(readLength(reader))
    }

    private fun readUnsigned(reader: RdpReader, tag: Int): Int {
        expectTag(reader, tag)
        val size = readLength(reader)
        // Five bytes are legal for a 32-bit value whose top bit is set: BER integers are signed, so
        // a conformant encoder prefixes a zero to keep it positive.
        if (size == 0 || size > MAX_INTEGER_BYTES + 1) {
            throw RdpProtocolException("BER integer of $size bytes is out of range")
        }
        var value = 0L
        repeat(size) { value = (value shl 8) or reader.u8().toLong() }
        if (value > 0xFFFFFFFFL) throw RdpProtocolException("BER integer does not fit 32 bits")
        return value.toInt()
    }

    private fun expectTag(reader: RdpReader, tag: Int) {
        val actual = reader.u8()
        if (actual != tag) {
            throw RdpProtocolException("expected BER tag 0x${tag.toString(16)}, got 0x${actual.toString(16)}")
        }
    }
}
