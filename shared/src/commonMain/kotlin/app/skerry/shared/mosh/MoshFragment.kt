package app.skerry.shared.mosh

/**
 * mosh's transport fragmentation layer: an instruction (zlib-compressed protobuf) is split
 * into datagram-sized fragments. Header: 8-byte big-endian instruction id, then a 2-byte
 * big-endian fragment index whose top bit marks the final fragment.
 */
data class MoshFragment(
    val id: ULong,
    val index: Int,
    val final: Boolean,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + data.size)
        for (i in 0 until 8) out[i] = (id shr ((7 - i) * 8)).toByte()
        val num = index or if (final) 0x8000 else 0
        out[8] = (num shr 8).toByte()
        out[9] = num.toByte()
        data.copyInto(out, HEADER_SIZE)
        return out
    }

    companion object {
        const val HEADER_SIZE = 10

        fun parse(datagram: ByteArray): MoshFragment? {
            if (datagram.size < HEADER_SIZE) return null
            var id = 0uL
            for (i in 0 until 8) id = (id shl 8) or (datagram[i].toULong() and 0xFFu)
            val num = ((datagram[8].toInt() and 0xFF) shl 8) or (datagram[9].toInt() and 0xFF)
            return MoshFragment(
                id = id,
                index = num and 0x7FFF,
                final = num and 0x8000 != 0,
                data = datagram.copyOfRange(HEADER_SIZE, datagram.size),
            )
        }
    }
}

/** Splits outgoing payloads into fragments under one monotonically growing instruction id. */
class MoshFragmenter {
    private var nextId = 0uL

    fun split(payload: ByteArray, maxFragmentSize: Int): List<MoshFragment> {
        require(maxFragmentSize > 0) { "maxFragmentSize must be positive" }
        val id = nextId++
        val fragments = mutableListOf<MoshFragment>()
        var offset = 0
        var index = 0
        do {
            val end = minOf(offset + maxFragmentSize, payload.size)
            val last = end == payload.size
            fragments += MoshFragment(id, index++, last, payload.copyOfRange(offset, end))
            offset = end
        } while (!last)
        return fragments
    }
}

/**
 * Reassembles incoming fragments. Only the newest instruction id is tracked (mosh sends one
 * instruction at a time); fragments of older ids are dropped, duplicates ignored.
 */
class MoshFragmentAssembler {
    private var currentId = 0uL
    private var pieces = HashMap<Int, ByteArray>()
    private var finalIndex = -1

    /** Returns the full payload once every fragment of the newest instruction has arrived. */
    fun add(fragment: MoshFragment): ByteArray? {
        if (fragment.id < currentId) return null
        if (fragment.id > currentId) {
            currentId = fragment.id
            pieces = HashMap()
            finalIndex = -1
        }
        pieces.putIfAbsent(fragment.index, fragment.data)
        if (fragment.final) finalIndex = fragment.index
        if (finalIndex < 0 || pieces.size != finalIndex + 1) return null
        val total = ByteArray(pieces.values.sumOf { it.size })
        var offset = 0
        for (i in 0..finalIndex) {
            val piece = pieces[i] ?: return null
            piece.copyInto(total, offset)
            offset += piece.size
        }
        // Consume: a duplicate of the final fragment must not re-emit the payload.
        currentId++
        pieces = HashMap()
        finalIndex = -1
        return total
    }
}
