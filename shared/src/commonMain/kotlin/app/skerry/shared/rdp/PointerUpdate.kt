package app.skerry.shared.rdp

/**
 * Pointer updates (MS-RDPBCGR 2.2.9.1.1.4): the remote cursor's shape, carried as a colour (XOR)
 * mask plus a 1-bit transparency (AND) mask.
 *
 * The two masks encode four states per pixel; the fourth — AND=1 with a non-zero XOR, "invert
 * whatever is on screen", the text I-beam — has no equivalent in an ARGB sprite, so those pixels
 * travel on [RdpUpdate.PointerShape.invert] and the view composites them with a difference blend.
 *
 * A 32-bit shape (what Windows sends for every modern cursor) usually carries real per-pixel alpha
 * in the XOR data with an all-zero AND mask; when any alpha byte is set the alpha wins, and an
 * all-zero alpha plane means "no alpha here" and the AND mask decides, as FreeRDP does.
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

        if (xorBitsPerPixel !in SUPPORTED_DEPTHS) {
            throw RdpProtocolException("unsupported pointer depth $xorBitsPerPixel")
        }
        val planes = PointerMasks(xorMask, andMask, width, height, xorBitsPerPixel).decode()
        val pointer = RdpUpdate.PointerShape(
            planes.argb,
            width,
            height,
            hotspotX.coerceIn(0, width - 1),
            hotspotY.coerceIn(0, height - 1),
            planes.invert,
        )
        // Filed before it is returned: the server switches back to this shape by index alone.
        cache.put(cacheIndex, pointer)
        return pointer
    }

    private class PointerPlanes(val argb: IntArray, val invert: IntArray?)

    /** The two wire masks of one shape, with the geometry every pixel read derives from. */
    private class PointerMasks(
        private val xorMask: ByteArray,
        private val andMask: ByteArray,
        private val width: Int,
        private val height: Int,
        private val bitsPerPixel: Int,
    ) {
        private val bytesPerPixel = (bitsPerPixel + 7) / 8

        // Both masks are stored bottom-up with each row padded to a two-byte boundary.
        private val xorStride = ((width * bitsPerPixel + 7) / 8 + 1) and 1.inv()
        private val andStride = ((width + 7) / 8 + 1) and 1.inv()

        fun decode(): PointerPlanes {
            val alpha = bitsPerPixel == 32 && hasAlphaPlane()
            val argb = IntArray(width * height)
            var invert: IntArray? = null
            for (row in 0 until height) {
                val sourceRow = height - 1 - row
                for (column in 0 until width) {
                    val index = row * width + column
                    if (alpha) {
                        argb[index] = readArgbPixel(sourceRow * xorStride, column)
                        continue
                    }
                    val transparent = maskBit(sourceRow * andStride, column)
                    val color = readXorPixel(sourceRow * xorStride, column)
                    when {
                        !transparent -> argb[index] = OPAQUE or color
                        color == 0 -> Unit // AND=1, XOR=0: fully transparent
                        // AND=1 with colour: the invert case — see the class note.
                        else -> {
                            val plane = invert ?: IntArray(width * height).also { invert = it }
                            plane[index] = OPAQUE or INVERTED
                        }
                    }
                }
            }
            return PointerPlanes(argb, invert)
        }

        private fun maskBit(rowOffset: Int, column: Int): Boolean {
            val index = rowOffset + column / 8
            // Missing mask data reads as OPAQUE: a truncated shape then shows whatever colour data
            // arrived, where transparent made the whole cursor invisible (F-24/F-42).
            if (index !in andMask.indices) return false
            return (andMask[index].toInt() shr (7 - column % 8)) and 1 == 1
        }

        /** Whether any pixel of a 32-bit XOR plane carries a non-zero alpha byte. */
        private fun hasAlphaPlane(): Boolean {
            for (row in 0 until height) {
                for (column in 0 until width) {
                    val index = row * xorStride + column * 4 + 3
                    if (index in xorMask.indices && xorMask[index].toInt() != 0) return true
                }
            }
            return false
        }

        /** One 32-bit XOR pixel with its own alpha byte kept (the modern-cursor path, F-19). */
        private fun readArgbPixel(rowOffset: Int, column: Int): Int {
            val index = rowOffset + column * 4
            if (index + 4 > xorMask.size) return 0
            fun byteAt(offset: Int) = xorMask[index + offset].toInt() and 0xFF
            return (byteAt(3) shl 24) or (byteAt(2) shl 16) or (byteAt(1) shl 8) or byteAt(0)
        }

        private fun readXorPixel(rowOffset: Int, column: Int): Int {
            if (bitsPerPixel == 1) {
                val index = rowOffset + column / 8
                if (index !in xorMask.indices) return 0
                return if ((xorMask[index].toInt() shr (7 - column % 8)) and 1 == 1) 0xFFFFFF else 0
            }
            val index = rowOffset + column * bytesPerPixel
            if (index + bytesPerPixel > xorMask.size) return 0
            fun byteAt(offset: Int) = xorMask[index + offset].toInt() and 0xFF
            return when (bytesPerPixel) {
                1 -> byteAt(0) * 0x010101 // an 8-bit pointer without a palette renders as grey
                2 -> InterleavedRle.rgb565ToArgb(byteAt(0) or (byteAt(1) shl 8)) and 0xFFFFFF
                else -> byteAt(0) or (byteAt(1) shl 8) or (byteAt(2) shl 16)
            }
        }
    }

    private val SUPPORTED_DEPTHS = setOf(1, 8, 16, 24, 32)

    private const val OPAQUE = 0xFF shl 24
    private const val INVERTED = 0xFFFFFF
}
