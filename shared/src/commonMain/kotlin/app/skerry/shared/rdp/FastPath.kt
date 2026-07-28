package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer

/**
 * Fast-path server updates (MS-RDPBCGR 2.2.9.1.2) — the framing every modern server sends graphics
 * and pointer changes in. One packet carries a run of updates, each with its own type, and a single
 * logical update may be split across packets, which the fragment buffer here reassembles.
 */
class FastPathDecoder(
    private val framebuffer: RemoteFramebuffer,
    /**
     * The session's colour table, owned by the caller. An 8-bit session's palette can arrive on
     * either path and be used by the other, so both decoders read and write the same one — two
     * copies would decode half the bitmaps against a stale table and simply show wrong colours.
     */
    private val palette: SessionPalette,
) {
    private var fragmentType = -1
    private val fragments = mutableListOf<ByteArray>()
    private var fragmentBytes = 0

    /**
     * Decode one fast-path packet (header included) into the updates it carries, applying graphics
     * to the framebuffer as it goes.
     *
     * @throws RdpProtocolException the packet is malformed or a fragment run grew past what a single
     * update may be
     */
    fun decode(packet: ByteArray, surfaceDecoder: SurfaceDecoder): List<RdpUpdate> {
        val header = packet[0].toInt() and 0xFF
        val encrypted = header and (FASTPATH_OUTPUT_ENCRYPTED shl 6) != 0
        if (encrypted) {
            // Encryption here is the legacy RDP security layer, which this client never negotiates.
            throw RdpProtocolException("fast-path update is encrypted with the legacy security layer")
        }
        val lengthByte = packet[1].toInt() and 0xFF
        val reader = RdpReader(packet, if (lengthByte and 0x80 != 0) 3 else 2)

        val updates = mutableListOf<RdpUpdate>()
        while (reader.remaining > 0) {
            val updateHeader = reader.u8()
            val updateCode = updateHeader and 0x0F
            val fragmentation = (updateHeader shr 4) and 0x03
            val compression = (updateHeader shr 6) and 0x03
            if (compression == FASTPATH_OUTPUT_COMPRESSION_USED) {
                // Bulk compression (MPPC) is only offered when the client asks for it in the
                // General capability set, and this one does not.
                throw RdpProtocolException("compressed fast-path update, which was never negotiated")
            }
            val size = reader.u16le()
            val body = reader.bytes(minOf(size, reader.remaining))
            val assembled = assemble(updateCode, fragmentation, body) ?: continue
            updates += decodeUpdate(updateCode, RdpReader(assembled), surfaceDecoder)
        }
        return updates
    }

    /**
     * Collect fragments of one logical update. Returns the whole payload once the run is complete,
     * or null while it is still arriving.
     */
    private fun assemble(updateCode: Int, fragmentation: Int, body: ByteArray): ByteArray? = when (fragmentation) {
        FASTPATH_FRAGMENT_SINGLE -> body
        FASTPATH_FRAGMENT_FIRST -> {
            fragments.clear()
            fragmentBytes = 0
            fragmentType = updateCode
            addFragment(body)
            null
        }

        FASTPATH_FRAGMENT_NEXT -> {
            requireFragmentRun(updateCode)
            addFragment(body)
            null
        }

        else -> {
            requireFragmentRun(updateCode)
            addFragment(body)
            val whole = ByteArray(fragmentBytes)
            var offset = 0
            for (fragment in fragments) {
                fragment.copyInto(whole, offset)
                offset += fragment.size
            }
            fragments.clear()
            fragmentBytes = 0
            fragmentType = -1
            whole
        }
    }

    private fun requireFragmentRun(updateCode: Int) {
        if (fragmentType != updateCode) {
            throw RdpProtocolException("fast-path fragment of type $updateCode outside a run of $fragmentType")
        }
    }

    private fun addFragment(body: ByteArray) {
        // The cap is the multifragment size this client advertised; without it a server could grow
        // this buffer without limit by never sending a final fragment.
        if (fragmentBytes + body.size > MAX_FRAGMENTED_UPDATE) {
            fragments.clear()
            fragmentBytes = 0
            fragmentType = -1
            throw RdpProtocolException("fragmented update larger than the advertised maximum")
        }
        fragments.add(body)
        fragmentBytes += body.size
    }

    private fun decodeUpdate(updateCode: Int, reader: RdpReader, surfaceDecoder: SurfaceDecoder): List<RdpUpdate> =
        when (updateCode) {
            UPDATETYPE_BITMAP -> {
                reader.u16le() // updateType, repeated inside the payload
                listOf(BitmapUpdate.apply(reader, framebuffer, palette.colors))
            }

            UPDATETYPE_PALETTE -> {
                reader.u16le() // updateType
                palette.colors = BitmapUpdate.readPalette(reader)
                emptyList()
            }

            UPDATETYPE_SURFCMDS -> surfaceDecoder.decode(reader, framebuffer)
            UPDATETYPE_PTR_NULL -> listOf(RdpUpdate.PointerVisible(false))
            UPDATETYPE_PTR_DEFAULT -> listOf(RdpUpdate.PointerVisible(true))
            UPDATETYPE_PTR_POSITION -> listOf(RdpUpdate.PointerPosition(reader.u16le(), reader.u16le()))
            UPDATETYPE_COLOR_POINTER -> listOf(PointerUpdate.colorPointer(reader))
            UPDATETYPE_POINTER -> listOf(PointerUpdate.newPointer(reader))
            UPDATETYPE_LARGE_POINTER -> listOf(PointerUpdate.largePointer(reader))
            // Cached pointers reference a slot this client never fills: it does not claim a pointer
            // cache beyond the minimum, so the honest answer is to leave the cursor as it is.
            UPDATETYPE_CACHED_POINTER -> emptyList()
            UPDATETYPE_SYNCHRONIZE -> emptyList()
            UPDATETYPE_ORDERS ->
                // Orders were never claimed in the capability exchange; a server sending them anyway
                // is drawing something this client cannot execute, and silently dropping them would
                // leave a screen that is wrong rather than merely stale.
                throw RdpProtocolException("server sent drawing orders, which this client does not support")

            else -> emptyList()
        }

    private companion object {
        const val UPDATETYPE_ORDERS = 0x0
        const val UPDATETYPE_BITMAP = 0x1
        const val UPDATETYPE_PALETTE = 0x2
        const val UPDATETYPE_SYNCHRONIZE = 0x3
        const val UPDATETYPE_SURFCMDS = 0x4
        const val UPDATETYPE_PTR_NULL = 0x5
        const val UPDATETYPE_PTR_DEFAULT = 0x6
        const val UPDATETYPE_PTR_POSITION = 0x8
        const val UPDATETYPE_COLOR_POINTER = 0x9
        const val UPDATETYPE_CACHED_POINTER = 0xA
        const val UPDATETYPE_POINTER = 0xB
        const val UPDATETYPE_LARGE_POINTER = 0xC

        const val FASTPATH_FRAGMENT_SINGLE = 0
        const val FASTPATH_FRAGMENT_LAST = 1
        const val FASTPATH_FRAGMENT_FIRST = 2
        const val FASTPATH_FRAGMENT_NEXT = 3

        const val FASTPATH_OUTPUT_COMPRESSION_USED = 0x2
        const val FASTPATH_OUTPUT_ENCRYPTED = 0x2

        /** Matches the multifragment maximum advertised in [ClientCapabilities]. */
        const val MAX_FRAGMENTED_UPDATE = 0x3F0000
    }
}

/** Bitmap and palette updates (MS-RDPBCGR 2.2.9.1.1.3.1.2 and 2.2.9.1.1.3.1.1). */
object BitmapUpdate {
    private const val BITMAP_COMPRESSION = 0x0001
    private const val NO_BITMAP_COMPRESSION_HDR = 0x0400

    /** Apply a bitmap update to [framebuffer] and report the rectangles it changed. */
    fun apply(reader: RdpReader, framebuffer: RemoteFramebuffer, palette: IntArray?): RdpUpdate.Region {
        val count = reader.u16le()
        val rects = mutableListOf<RdpRect>()
        repeat(count) {
            val left = reader.u16le()
            val top = reader.u16le()
            val right = reader.u16le()
            val bottom = reader.u16le()
            val width = reader.u16le()
            val height = reader.u16le()
            val bitsPerPixel = reader.u16le()
            val flags = reader.u16le()
            val declaredLength = reader.u16le()

            var length = declaredLength
            if (flags and BITMAP_COMPRESSION != 0 && flags and NO_BITMAP_COMPRESSION_HDR == 0) {
                // The compression header repeats the length; the body is what follows it.
                reader.u16le() // cbCompFirstRowSize, always 0
                length = reader.u16le() // cbCompMainBodySize
                reader.u16le() // cbScanWidth
                reader.u16le() // cbUncompressedSize
            }
            val data = reader.bytes(minOf(length, reader.remaining))
            if (width <= 0 || height <= 0) return@repeat

            val bytesPerPixel = (bitsPerPixel + 7) / 8
            val pixels = try {
                decodeBitmap(data, width, height, bitsPerPixel, bytesPerPixel, flags, palette)
            } catch (e: RdpProtocolException) {
                // One rectangle this client cannot read is not worth the whole session: the pixels
                // under it stay as they were and the server repaints them on the next refresh,
                // which is a scarred screen instead of a dropped connection.
                return@repeat
            }
            blit(framebuffer, pixels, left, top, width, height)
            rects += RdpRect(left, top, width, height)
        }
        return RdpUpdate.Region(rects)
    }

    private fun decodeBitmap(
        data: ByteArray,
        width: Int,
        height: Int,
        bitsPerPixel: Int,
        bytesPerPixel: Int,
        flags: Int,
        palette: IntArray?,
    ): IntArray {
        // Before anything allocates: the rectangle's size is the server's to declare, and the
        // pixels are allocated from it before a byte of the payload is read.
        RdpImageBounds.requireSize(width, height, "a bitmap update")
        // Interleaved RLE covers 8, 15, 16 and 24-bit sessions; a 32-bit one compresses with the
        // planar codec instead, and the depth is the only thing that says which.
        return when {
            flags and BITMAP_COMPRESSION != 0 && bytesPerPixel == 4 ->
                PlanarCodec.decode(data, width, height)

            flags and BITMAP_COMPRESSION != 0 ->
                InterleavedRle.decode(data, width, height, bytesPerPixel, palette)

            else -> rawBitmap(data, width, height, bytesPerPixel, palette)
        }
    }

    /** The 256-entry palette of an 8-bit session (RGB triplets). */
    fun readPalette(reader: RdpReader): IntArray {
        reader.skip(2) // pad2Octets
        val count = reader.u32le()
        if (count !in 0..256) throw RdpProtocolException("palette of $count entries")
        val palette = IntArray(256)
        repeat(count) { index ->
            val red = reader.u8()
            val green = reader.u8()
            val blue = reader.u8()
            palette[index] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return palette
    }

    /** Uncompressed bitmap data: bottom-up rows, each padded to a four-byte boundary. */
    private fun rawBitmap(
        data: ByteArray,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        palette: IntArray?,
    ): IntArray {
        val out = IntArray(width * height)
        val reader = RdpReader(data)
        for (row in height - 1 downTo 0) {
            for (column in 0 until width) {
                if (reader.remaining < bytesPerPixel) return out
                out[row * width + column] = when (bytesPerPixel) {
                    1 -> palette?.getOrNull(reader.u8()) ?: (0xFF shl 24)
                    2 -> InterleavedRle.rgb565ToArgb(reader.u16le())
                    3 -> {
                        val blue = reader.u8()
                        val green = reader.u8()
                        val red = reader.u8()
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }

                    else -> {
                        val blue = reader.u8()
                        val green = reader.u8()
                        val red = reader.u8()
                        reader.u8() // the alpha byte is not meaningful in an RDP session
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
            }
        }
        return out
    }

    private fun blit(framebuffer: RemoteFramebuffer, pixels: IntArray, x: Int, y: Int, width: Int, height: Int) {
        for (row in 0 until height) {
            framebuffer.blitRow(x, y + row, width, pixels, row * width)
        }
    }
}

/**
 * The colour table of an 8-bit session. A holder rather than a field because the palette arrives on
 * whichever path the server prefers and is then used by both.
 */
class SessionPalette {
    var colors: IntArray? = null
}
