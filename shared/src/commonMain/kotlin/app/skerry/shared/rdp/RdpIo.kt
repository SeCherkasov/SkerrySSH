package app.skerry.shared.rdp

/**
 * Byte source the RDP codec pulls from: [readFully] suspends until exactly [len] bytes land in [dst]
 * at [offset], or throws on EOF/close. Same pull model (and same reasoning) as `VncSource` — RDP's
 * structures nest deeply, and absorbing partial socket reads here keeps every decoder straight-line.
 */
fun interface RdpSource {
    suspend fun readFully(dst: ByteArray, offset: Int, len: Int)
}

/** Byte sink the codec writes client→server PDUs to. */
fun interface RdpSink {
    suspend fun write(bytes: ByteArray)
}

/**
 * Growing byte buffer for building PDUs. RDP mixes byte orders — ASN.1/TPKT/X.224 are big-endian,
 * everything RDP-native is little-endian — so the order is part of every method name and never
 * defaulted; a silent default is exactly how a field ends up byte-swapped on the wire.
 */
class RdpWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
    private var length = 0

    /** Bytes written so far. */
    val size: Int get() = length

    fun u8(value: Int): RdpWriter = apply {
        ensure(1)
        buf[length++] = value.toByte()
    }

    fun u16le(value: Int): RdpWriter = apply {
        ensure(2)
        buf[length++] = value.toByte()
        buf[length++] = (value ushr 8).toByte()
    }

    fun u16be(value: Int): RdpWriter = apply {
        ensure(2)
        buf[length++] = (value ushr 8).toByte()
        buf[length++] = value.toByte()
    }

    fun u32le(value: Int): RdpWriter = apply {
        ensure(4)
        buf[length++] = value.toByte()
        buf[length++] = (value ushr 8).toByte()
        buf[length++] = (value ushr 16).toByte()
        buf[length++] = (value ushr 24).toByte()
    }

    fun u32be(value: Int): RdpWriter = apply {
        ensure(4)
        buf[length++] = (value ushr 24).toByte()
        buf[length++] = (value ushr 16).toByte()
        buf[length++] = (value ushr 8).toByte()
        buf[length++] = value.toByte()
    }

    fun bytes(source: ByteArray, offset: Int = 0, count: Int = source.size - offset): RdpWriter = apply {
        ensure(count)
        source.copyInto(buf, length, offset, offset + count)
        length += count
    }

    /** [count] zero bytes — RDP structures are full of fixed-size reserved/padding fields. */
    fun zeros(count: Int): RdpWriter = apply {
        ensure(count)
        buf.fill(0, length, length + count)
        length += count
    }

    /**
     * UTF-16LE text, as every string field in the RDP connection sequence is encoded.
     * [nullTerminated] adds the trailing 0x0000 that the length fields of those structures exclude.
     */
    fun utf16le(text: String, nullTerminated: Boolean = false): RdpWriter = apply {
        for (ch in text) u16le(ch.code)
        if (nullTerminated) u16le(0)
    }

    /**
     * Overwrite the 16-bit little-endian field at [offset] — used for lengths that are only known
     * once the body has been written (the alternative, building every body into its own buffer first,
     * costs a copy per nesting level).
     */
    fun patchU16le(offset: Int, value: Int) {
        require(offset >= 0 && offset + 2 <= length) { "patch out of range" }
        buf[offset] = value.toByte()
        buf[offset + 1] = (value ushr 8).toByte()
    }

    /** Overwrite the 16-bit big-endian field at [offset] (TPKT length, ASN.1 lengths). */
    fun patchU16be(offset: Int, value: Int) {
        require(offset >= 0 && offset + 2 <= length) { "patch out of range" }
        buf[offset] = (value ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(length)

    private fun ensure(extra: Int) {
        require(extra >= 0) { "negative length" }
        if (length + extra <= buf.size) return
        var capacity = buf.size
        while (capacity < length + extra) capacity *= 2
        buf = buf.copyOf(capacity)
    }
}

/**
 * Bounds-checked reader over a received PDU. Every accessor throws [RdpProtocolException] rather than
 * an index exception: the buffer holds bytes a remote peer chose, so "ran off the end" is a protocol
 * error to report, not a crash to propagate.
 */
class RdpReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    /** Bytes left to read. */
    val remaining: Int get() = end - pos

    /** Current read offset (used to bound a nested structure by its declared length). */
    val position: Int get() = pos

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    /** The next byte without consuming it — used where a tag decides how to read what follows. */
    fun peekU8(): Int {
        need(1)
        return buf[pos].toInt() and 0xFF
    }

    fun u16le(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u16be(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u32le(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun u32be(): Int {
        need(4)
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        val out = buf.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun skip(count: Int) {
        need(count)
        pos += count
    }

    /**
     * Un-read [count] bytes. Needed where a field can only be recognised after reading it — the
     * licensing phase ends with a PDU that has no security header, and telling the two apart means
     * reading four bytes and sometimes putting them back.
     */
    fun rewind(count: Int) {
        if (count < 0 || pos - count < 0) throw RdpProtocolException("cannot rewind $count bytes")
        pos -= count
    }

    /** The rest of the buffer, consumed. */
    fun rest(): ByteArray = bytes(remaining)

    /**
     * A reader over the next [count] bytes, which are also consumed here. Nested structures declare
     * their own length, and giving the sub-decoder its own bound is what stops an inner field from
     * eating the outer PDU's bytes when the declared length lies.
     */
    fun slice(count: Int): RdpReader {
        need(count)
        val view = RdpReader(buf, pos, pos + count)
        pos += count
        return view
    }

    private fun need(count: Int) {
        if (count < 0) throw RdpProtocolException("negative read length $count")
        // Subtraction, not `pos + count > end`: both sides of that sum are bounded by the buffer
        // only after the check, and a length near Int.MAX_VALUE wraps it negative and passes.
        if (count > end - pos) {
            throw RdpProtocolException("truncated PDU: need $count bytes at $pos, have $remaining")
        }
    }
}
