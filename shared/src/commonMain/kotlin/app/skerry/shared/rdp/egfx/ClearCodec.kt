package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.NsCodec
import app.skerry.shared.rdp.RdpImageBounds
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader

/**
 * ClearCodec (MS-RDPEGFX 2.2.4.1) — the codec Windows reaches for on the parts of the screen that
 * are drawn rather than photographed: window chrome, text, icons.
 *
 * An image arrives as up to three layers painted one over the other. The residual layer is a plain
 * run-length sweep of the whole bitmap; the bands layer overwrites vertical strips of it, column by
 * column; and the subcodec layer drops rectangles on top. What makes the codec worth its complexity
 * is that the bands layer barely sends pixels at all — a column ("V-Bar") is usually a reference to
 * one the client already holds, which is how a scrolling list of text costs almost nothing.
 *
 * That means the decoder is stateful across the whole connection, in three caches the server tracks
 * a copy of: whole V-Bars, the shorter runs a V-Bar is built from, and decoded glyphs. Each has its
 * own cursor, and the server names entries by index, never by content. A cursor that drifts from the
 * server's would silently paint the wrong pixels forever, which is why the two writing paths below
 * (short V-Bar hit and short V-Bar miss) both advance the V-Bar cursor and the cache-hit path does
 * not — that asymmetry is the whole synchronisation contract.
 */
class ClearCodec {

    /** Whole columns, keyed by the index the server assigns as it fills them. */
    private val vBars = arrayOfNulls<IntArray>(VBAR_ENTRIES)
    private var vBarCursor = 0

    /** The pixel runs a column is assembled from — shorter than the band, padded with background. */
    private val shortVBars = arrayOfNulls<IntArray>(SHORT_VBAR_ENTRIES)
    private var shortVBarCursor = 0

    /**
     * Decoded images the server may replay by index. A glyph is stored as a flat run of pixels with
     * no dimensions of its own: the rectangle of the replaying message decides its shape.
     */
    private val glyphs = arrayOfNulls<IntArray>(GLYPH_SLOTS)

    /** Decode one bitmap stream into a [width]x[height] image of opaque ARGB pixels. */
    fun decode(data: ByteArray, width: Int, height: Int): IntArray {
        RdpImageBounds.requireSize(width, height, "a ClearCodec image")
        val reader = RdpReader(data)
        val flags = reader.u8()
        reader.u8() // seqNumber: it only lets a server detect a client that reordered messages
        if (flags and FLAG_CACHE_RESET != 0) {
            vBarCursor = 0
            shortVBarCursor = 0
        }

        val glyphIndex = if (flags and FLAG_GLYPH_INDEX != 0) reader.u16le() else null
        if (glyphIndex != null && glyphIndex >= GLYPH_SLOTS) {
            throw RdpProtocolException("a ClearCodec glyph index of $glyphIndex")
        }
        if (glyphIndex != null && flags and FLAG_GLYPH_HIT != 0) return glyph(glyphIndex, width, height)

        val image = IntArray(width * height)
        val residualByteCount = reader.u32le()
        val bandsByteCount = reader.u32le()
        val subcodecByteCount = reader.u32le()
        if (residualByteCount < 0 || bandsByteCount < 0 || subcodecByteCount < 0) {
            throw RdpProtocolException("a ClearCodec layer of negative length")
        }
        residual(reader.slice(residualByteCount), image)
        bands(reader.slice(bandsByteCount), image, width, height)
        subcodecs(reader.slice(subcodecByteCount), image, width, height)

        // The specification forbids a glyph larger than this, so a stream that asks for one is
        // asking the client to hold something the server will not replay.
        if (glyphIndex != null && image.size <= GLYPH_MAX_PIXELS) glyphs[glyphIndex] = image.copyOf()
        return image
    }

    /**
     * The image held at [index], reshaped to the rectangle this message names. A slot the server
     * believes it filled but this client has not is a desynchronised cache rather than a malformed
     * stream: painting the missing pixels black loses a glyph, where refusing loses the session.
     */
    private fun glyph(index: Int, width: Int, height: Int): IntArray {
        val stored = glyphs[index]
        if (stored != null && stored.size == width * height) return stored.copyOf()
        return IntArray(width * height) { if (stored != null && it < stored.size) stored[it] else OPAQUE }
    }

    /** The first layer: runs of one colour, laid across the whole image left to right, top to bottom. */
    private fun residual(reader: RdpReader, image: IntArray) {
        var index = 0
        while (reader.remaining >= RESIDUAL_SEGMENT_SIZE) {
            val argb = reader.bgr()
            val runLength = reader.runLength()
            if (runLength > image.size - index) {
                throw RdpProtocolException("a ClearCodec run of $runLength pixels past the image")
            }
            image.fill(argb, index, index + runLength)
            index += runLength
        }
    }

    /** The second layer: horizontal strips, each one column of pixels at a time. */
    private fun bands(reader: RdpReader, image: IntArray, width: Int, height: Int) {
        while (reader.remaining >= BAND_HEADER_SIZE) {
            val xStart = reader.u16le()
            val xEnd = reader.u16le()
            val yStart = reader.u16le()
            val yEnd = reader.u16le()
            val background = reader.bgr()
            if (xEnd < xStart || yEnd < yStart) {
                throw RdpProtocolException("a ClearCodec band from ($xStart, $yStart) to ($xEnd, $yEnd)")
            }
            val barHeight = yEnd - yStart + 1
            if (barHeight > MAX_BAND_HEIGHT) {
                throw RdpProtocolException("a ClearCodec band $barHeight pixels tall")
            }
            for (x in xStart..xEnd) {
                val column = vBar(reader, barHeight, background)
                if (x >= width) continue
                for (y in 0 until barHeight) {
                    val row = yStart + y
                    if (row < height) image[row * width + x] = column[y]
                }
            }
        }
    }

    /** One column of a band, from the packet or from either cache. */
    private fun vBar(reader: RdpReader, barHeight: Int, background: Int): IntArray {
        val header = reader.u16le()
        return when {
            header and VBAR_HIT_MASK != 0 -> cachedVBar(header and VBAR_INDEX_MASK, barHeight, background)

            header and SHORT_VBAR_KIND_MASK == SHORT_VBAR_HIT -> {
                val stored = shortVBars[header and SHORT_VBAR_INDEX_MASK] ?: EMPTY
                storeVBar(buildVBar(stored, reader.u8(), barHeight, background))
            }

            else -> {
                val yOn = header and 0xFF
                val yOff = (header ushr 8) and SHORT_VBAR_YOFF_MASK
                if (yOff < yOn) throw RdpProtocolException("a ClearCodec short V-Bar from $yOn to $yOff")
                val pixels = IntArray(yOff - yOn) { reader.bgr() }
                shortVBars[shortVBarCursor] = pixels
                shortVBarCursor = (shortVBarCursor + 1) % SHORT_VBAR_ENTRIES
                storeVBar(buildVBar(pixels, yOn, barHeight, background))
            }
        }
    }

    /**
     * An entry the server says this client already holds. As with a glyph, an entry that is missing
     * or the wrong height means the two caches have drifted apart; the band's own background is what
     * the surrounding pixels are anyway, so it is the least visible thing to put there.
     */
    private fun cachedVBar(index: Int, barHeight: Int, background: Int): IntArray {
        val stored = vBars[index]
        if (stored != null && stored.size == barHeight) return stored
        return IntArray(barHeight) { if (stored != null && it < stored.size) stored[it] else background }
    }

    /** A column is a short run of pixels at [yOn], with the band's background above and below it. */
    private fun buildVBar(pixels: IntArray, yOn: Int, barHeight: Int, background: Int): IntArray =
        IntArray(barHeight) { y -> if (y >= yOn && y - yOn < pixels.size) pixels[y - yOn] else background }

    private fun storeVBar(column: IntArray): IntArray {
        vBars[vBarCursor] = column
        vBarCursor = (vBarCursor + 1) % VBAR_ENTRIES
        return column
    }

    /** The third layer: rectangles carrying their own encoding, placed over what came before. */
    private fun subcodecs(reader: RdpReader, image: IntArray, width: Int, height: Int) {
        while (reader.remaining >= SUBCODEC_HEADER_SIZE) {
            val xStart = reader.u16le()
            val yStart = reader.u16le()
            val tileWidth = reader.u16le()
            val tileHeight = reader.u16le()
            val byteCount = reader.u32le()
            val subCodecId = reader.u8()
            if (byteCount < 0 || byteCount > reader.remaining) {
                throw RdpProtocolException("a ClearCodec subcodec of $byteCount bytes, ${reader.remaining} remain")
            }
            val body = reader.slice(byteCount)
            if (tileWidth <= 0 || tileHeight <= 0) continue
            // A tile's size is its own two fields, not the image's, so bounding the image on the way
            // in does not bound this. All three branches below allocate from the product before a
            // byte of the body is read.
            RdpImageBounds.requireSize(tileWidth, tileHeight, "a ClearCodec subcodec tile")
            val tile = when (subCodecId) {
                SUBCODEC_RAW -> IntArray(tileWidth * tileHeight) { if (body.remaining >= 3) body.bgr() else OPAQUE }
                SUBCODEC_NSCODEC -> NsCodec.decode(body, tileWidth, tileHeight)
                SUBCODEC_RLEX -> rlex(body, tileWidth, tileHeight)
                else -> throw RdpProtocolException("ClearCodec subcodec 0x${subCodecId.toString(16)}")
            }
            for (y in 0 until tileHeight) {
                val row = yStart + y
                if (row >= height) break
                for (x in 0 until tileWidth) {
                    val column = xStart + x
                    if (column < width) image[row * width + column] = tile[y * tileWidth + x]
                }
            }
        }
    }

    /**
     * RLEX (MS-RDPEGFX 2.2.4.1.1.3.1.1): palette indexes, run-length encoded, with a twist worth the
     * trouble on gradients — after each run comes a "suite", a stretch of indexes that count up by
     * one. Both are packed into a single byte whose split between index and suite depth follows from
     * how many palette entries there are.
     */
    private fun rlex(reader: RdpReader, width: Int, height: Int): IntArray {
        val paletteCount = reader.u8()
        if (paletteCount == 0 || paletteCount > MAX_PALETTE_ENTRIES) {
            throw RdpProtocolException("a ClearCodec palette of $paletteCount entries")
        }
        val palette = IntArray(paletteCount) { reader.bgr() }
        // floor(log2(paletteCount - 1)) + 1, and one bit when a single entry makes that undefined.
        val indexBits = if (paletteCount == 1) 1 else Int.SIZE_BITS - (paletteCount - 1).countLeadingZeroBits()

        val out = IntArray(width * height)
        var index = 0
        while (reader.remaining >= RLEX_SEGMENT_SIZE) {
            val packed = reader.u8()
            val stopIndex = packed and ((1 shl indexBits) - 1)
            val suiteDepth = (packed ushr indexBits) and ((1 shl (8 - indexBits)) - 1)
            val runLength = reader.runLength()
            val startIndex = stopIndex - suiteDepth
            if (startIndex < 0 || stopIndex >= paletteCount) {
                throw RdpProtocolException("a ClearCodec suite from $startIndex to $stopIndex of $paletteCount")
            }
            // In Long, and against the room that is left rather than the size: a run of two billion
            // added to an index overflows, and the segment then reads as one that fits.
            val segment = runLength.toLong() + suiteDepth + 1
            if (segment > out.size - index) {
                throw RdpProtocolException("a ClearCodec segment of $segment pixels past the tile")
            }
            out.fill(palette[startIndex], index, index + runLength)
            index += runLength
            for (entry in startIndex..stopIndex) out[index++] = palette[entry]
        }
        return out
    }

    /** One pixel as the codec writes them: blue, green, red, and an alpha this client supplies. */
    private fun RdpReader.bgr(): Int {
        val blue = u8()
        val green = u8()
        val red = u8()
        return OPAQUE or (red shl 16) or (green shl 8) or blue
    }

    /**
     * The run length shared by the residual and RLEX segments: one byte, escaping to two and then to
     * four. A four-byte length arrives as a signed Int here, so a server claiming a run of two
     * billion pixels reads back as negative and is caught where the run is used.
     */
    private fun RdpReader.runLength(): Int {
        val first = u8()
        if (first < 0xFF) return first
        val second = u16le()
        if (second < 0xFFFF) return second
        val third = u32le()
        if (third < 0) throw RdpProtocolException("a ClearCodec run length of $third")
        return third
    }

    private companion object {
        const val FLAG_GLYPH_INDEX = 0x01
        const val FLAG_GLYPH_HIT = 0x02
        const val FLAG_CACHE_RESET = 0x04

        const val SUBCODEC_RAW = 0x00
        const val SUBCODEC_NSCODEC = 0x01
        const val SUBCODEC_RLEX = 0x02

        /** Bit 15 of a V-Bar header marks a hit on a whole column; the rest is its index. */
        const val VBAR_HIT_MASK = 0x8000
        const val VBAR_INDEX_MASK = 0x7FFF

        /** With bit 15 clear, bit 14 tells a short V-Bar hit from a short V-Bar arriving inline. */
        const val SHORT_VBAR_KIND_MASK = 0xC000
        const val SHORT_VBAR_HIT = 0x4000
        const val SHORT_VBAR_INDEX_MASK = 0x3FFF
        const val SHORT_VBAR_YOFF_MASK = 0x3F

        const val VBAR_ENTRIES = 32768
        const val SHORT_VBAR_ENTRIES = 16384
        const val GLYPH_SLOTS = 4000
        const val GLYPH_MAX_PIXELS = 1024
        const val MAX_BAND_HEIGHT = 52
        const val MAX_PALETTE_ENTRIES = 0x7F

        const val RESIDUAL_SEGMENT_SIZE = 4
        const val BAND_HEADER_SIZE = 11
        const val SUBCODEC_HEADER_SIZE = 13
        const val RLEX_SEGMENT_SIZE = 2

        const val OPAQUE = 0xFF shl 24

        val EMPTY = IntArray(0)
    }
}
