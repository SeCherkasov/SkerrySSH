package app.skerry.shared.rdp

/**
 * Pointer updates (MS-RDPBCGR 2.2.9.1.1.4): the remote cursor's shape, carried as a colour (XOR)
 * mask plus a 1-bit transparency (AND) mask.
 *
 * The two masks encode four states per pixel, and the fourth has no equivalent in an ARGB sprite:
 * AND=1 with a non-zero XOR value means "invert whatever is on screen". Windows uses it for the
 * text I-beam over dark backgrounds. It is rendered as opaque white here — an inverting cursor that
 * cannot invert is better shown than dropped, which would leave the user without a caret at all.
 */
object PointerUpdate {

    /** Largest cursor accepted; the large-pointer capability caps shapes at 384×384. */
    private const val MAX_DIMENSION = 384

    fun colorPointer(reader: RdpReader, cache: PointerCache): RdpUpdate.PointerShape =
        shape(reader, xorBitsPerPixel = 24, wide = false, cache)

    /** A new-style pointer: the same structure with an explicit colour depth in front. */
    fun newPointer(reader: RdpReader, cache: PointerCache): RdpUpdate.PointerShape {
        val xorBpp = reader.u16le()
        return shape(reader, xorBpp, wide = false, cache)
    }

    /** A large pointer: same again, with 16-bit dimensions and 32-bit mask lengths. */
    fun largePointer(reader: RdpReader, cache: PointerCache): RdpUpdate.PointerShape {
        val xorBpp = reader.u16le()
        return shape(reader, xorBpp, wide = true, cache)
    }

    /**
     * A Cached Pointer Update (MS-RDPBCGR 2.2.9.1.1.4.6): a slot index, and the shape to show is the
     * one filed there. An empty slot is the one case with nothing to draw — the server named a shape
     * it never sent us — and the cursor stays as it is rather than being cleared.
     */
    fun cachedPointer(reader: RdpReader, cache: PointerCache): List<RdpUpdate> =
        cache.get(reader.u16le())?.let { listOf(it) } ?: emptyList()

    private fun shape(reader: RdpReader, xorBitsPerPixel: Int, wide: Boolean, cache: PointerCache): RdpUpdate.PointerShape {
        val cacheIndex = reader.u16le()
        val hotspotX = reader.u16le()
        val hotspotY = reader.u16le()
        val width = reader.u16le()
        val height = reader.u16le()
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            throw RdpProtocolException("pointer of ${width}x$height is out of range")
        }
        val andLength = if (wide) reader.u32le() else reader.u16le()
        val xorLength = if (wide) reader.u32le() else reader.u16le()
        if (andLength < 0 || xorLength < 0) throw RdpProtocolException("negative pointer mask length")
        val xorMask = reader.bytes(minOf(xorLength, reader.remaining))
        val andMask = reader.bytes(minOf(andLength, reader.remaining))

        val bytesPerPixel = when (xorBitsPerPixel) {
            1, 8, 16, 24, 32 -> (xorBitsPerPixel + 7) / 8
            else -> throw RdpProtocolException("unsupported pointer depth $xorBitsPerPixel")
        }
        // Both masks are stored bottom-up with each row padded to a two-byte boundary.
        val xorStride = ((width * xorBitsPerPixel + 7) / 8 + 1) and 1.inv()
        val andStride = ((width + 7) / 8 + 1) and 1.inv()

        val argb = IntArray(width * height)
        for (row in 0 until height) {
            val sourceRow = height - 1 - row
            for (column in 0 until width) {
                val transparent = maskBit(andMask, sourceRow * andStride, column)
                val color = readXorPixel(xorMask, sourceRow * xorStride, column, xorBitsPerPixel, bytesPerPixel)
                argb[row * width + column] = when {
                    !transparent -> OPAQUE or color
                    color == 0 -> 0 // AND=1, XOR=0: fully transparent
                    else -> OPAQUE or INVERTED // AND=1 with colour: the invert case (see the class note)
                }
            }
        }
        val pointer =
            RdpUpdate.PointerShape(argb, width, height, hotspotX.coerceIn(0, width - 1), hotspotY.coerceIn(0, height - 1))
        // Filed before it is returned: the server switches back to this shape by index alone.
        cache.put(cacheIndex, pointer)
        return pointer
    }

    private fun maskBit(mask: ByteArray, rowOffset: Int, column: Int): Boolean {
        val index = rowOffset + column / 8
        if (index !in mask.indices) return true // missing mask data reads as transparent
        return (mask[index].toInt() shr (7 - column % 8)) and 1 == 1
    }

    private fun readXorPixel(
        mask: ByteArray,
        rowOffset: Int,
        column: Int,
        bitsPerPixel: Int,
        bytesPerPixel: Int,
    ): Int {
        if (bitsPerPixel == 1) {
            val index = rowOffset + column / 8
            if (index !in mask.indices) return 0
            return if ((mask[index].toInt() shr (7 - column % 8)) and 1 == 1) 0xFFFFFF else 0
        }
        val index = rowOffset + column * bytesPerPixel
        if (index + bytesPerPixel > mask.size) return 0
        fun byteAt(offset: Int) = mask[index + offset].toInt() and 0xFF
        return when (bytesPerPixel) {
            1 -> byteAt(0) * 0x010101 // an 8-bit pointer without a palette renders as grey
            2 -> InterleavedRle.rgb565ToArgb(byteAt(0) or (byteAt(1) shl 8)) and 0xFFFFFF
            else -> byteAt(0) or (byteAt(1) shl 8) or (byteAt(2) shl 16)
        }
    }

    private const val OPAQUE = 0xFF shl 24
    private const val INVERTED = 0xFFFFFF
}
