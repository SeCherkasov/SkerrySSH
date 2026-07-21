package app.skerry.shared.vnc

/**
 * RFB rectangle decoders. Each reads its encoding's bytes from [VncSource] and writes ARGB pixels
 * into [VncFramebuffer]. They assume the canonical 32bpp pixel format the codec forces
 * ([CanonicalPixelFormat]), so pixel conversion is a single fixed operation. More encodings
 * (Hextile, ZRLE, Tight) are added in their own slices.
 */

/** Raw: `width*height` pixels, row-major, 4 bytes each (our forced format). */
internal suspend fun decodeRaw(source: VncSource, fb: VncFramebuffer, rect: VncRect) {
    if (rect.width <= 0 || rect.height <= 0) return
    val rowBytes = rect.width * CanonicalPixelFormat.PIXEL_BYTES
    val rowBuf = ByteArray(rowBytes)
    val argbRow = IntArray(rect.width)
    var row = 0
    while (row < rect.height) {
        source.readFully(rowBuf, 0, rowBytes)
        var col = 0
        var off = 0
        while (col < rect.width) {
            argbRow[col] = CanonicalPixelFormat.argbFromPixel(rowBuf, off)
            col++
            off += CanonicalPixelFormat.PIXEL_BYTES
        }
        fb.blitRow(rect.x, rect.y + row, rect.width, argbRow, 0)
        row++
    }
}

/** CopyRect: a u16 source X/Y — move an existing block of the framebuffer to this rect. */
internal suspend fun decodeCopyRect(source: VncSource, fb: VncFramebuffer, rect: VncRect) {
    val buf = ByteArray(4)
    source.readFully(buf, 0, 4)
    val srcX = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    val srcY = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
    fb.copyRect(srcX, srcY, rect.x, rect.y, rect.width, rect.height)
}

// Cap on a single ZRLE compressed chunk before allocation — bounds memory against a hostile server
// (the inflated output is separately capped in the Inflater). Generous vs. a full-screen ZRLE update.
private const val MAX_ZRLE_CHUNK = 16 * 1024 * 1024

// Hextile subencoding mask bits.
private const val HEXTILE_RAW = 1
private const val HEXTILE_BG = 2
private const val HEXTILE_FG = 4
private const val HEXTILE_ANY_SUBRECTS = 8
private const val HEXTILE_SUBRECTS_COLOURED = 16

/**
 * Hextile: the rectangle is split into 16×16 tiles (left→right, top→bottom). Each tile has a
 * subencoding mask; Raw tiles carry full pixels, otherwise a background fill plus optional
 * foreground subrectangles. Background/foreground colours persist across tiles unless re-specified.
 * All pixels are full 4-byte pixels (our canonical format), not CPIXELs.
 */
internal suspend fun decodeHextile(source: VncSource, fb: VncFramebuffer, rect: VncRect) {
    val one = ByteArray(1)
    val px = ByteArray(CanonicalPixelFormat.PIXEL_BYTES)
    val rawBuf = ByteArray(16 * 16 * CanonicalPixelFormat.PIXEL_BYTES)
    val argbRow = IntArray(16)

    suspend fun u8(): Int { source.readFully(one, 0, 1); return one[0].toInt() and 0xFF }
    suspend fun pixel(): Int { source.readFully(px, 0, px.size); return CanonicalPixelFormat.argbFromPixel(px, 0) }

    var bg = 0xFF000000.toInt()
    var fg = 0xFFFFFFFF.toInt()

    var tileY = rect.y
    val endY = rect.y + rect.height
    while (tileY < endY) {
        val th = minOf(16, endY - tileY)
        var tileX = rect.x
        val endX = rect.x + rect.width
        while (tileX < endX) {
            val tw = minOf(16, endX - tileX)
            val sub = u8()
            if (sub and HEXTILE_RAW != 0) {
                val n = tw * th * CanonicalPixelFormat.PIXEL_BYTES
                source.readFully(rawBuf, 0, n)
                var row = 0
                while (row < th) {
                    var col = 0
                    while (col < tw) {
                        argbRow[col] = CanonicalPixelFormat.argbFromPixel(rawBuf, (row * tw + col) * CanonicalPixelFormat.PIXEL_BYTES)
                        col++
                    }
                    fb.blitRow(tileX, tileY + row, tw, argbRow, 0)
                    row++
                }
            } else {
                if (sub and HEXTILE_BG != 0) bg = pixel()
                fb.fillRect(tileX, tileY, tw, th, bg)
                if (sub and HEXTILE_FG != 0) fg = pixel()
                if (sub and HEXTILE_ANY_SUBRECTS != 0) {
                    val nSub = u8()
                    repeat(nSub) {
                        val colour = if (sub and HEXTILE_SUBRECTS_COLOURED != 0) pixel() else fg
                        val xy = u8()
                        val wh = u8()
                        val sx = xy shr 4
                        val sy = xy and 0x0F
                        val sw = (wh shr 4) + 1
                        val sh = (wh and 0x0F) + 1
                        fb.fillRect(tileX + sx, tileY + sy, sw, sh, colour)
                    }
                }
            }
            tileX += 16
        }
        tileY += 16
    }
}

/** Sequential reader over an in-memory (already-inflated) byte buffer. */
private class ByteCursor(private val d: ByteArray) {
    var pos = 0
        private set
    fun u8(): Int = d[pos++].toInt() and 0xFF
    fun cpixel(): Int {
        val argb = CanonicalPixelFormat.argbFromCPixel(d, pos)
        pos += CanonicalPixelFormat.CPIXEL_BYTES
        return argb
    }
    /** RLE run length: bytes summed while == 255; the run is that sum + 1. */
    fun runLength(): Int {
        var len = 1
        var b: Int
        do { b = u8(); len += b } while (b == 255)
        return len
    }
}

/**
 * ZRLE (16): a u32 length then that many zlib bytes fed to the connection-persistent [inflater]
 * (the stream's history spans the whole connection). The inflated data is 64×64 tiles, each with a
 * subencoding: raw CPIXELs / solid colour / packed palette / plain RLE / palette RLE. Pixels are
 * CPIXELs (3 bytes for our format).
 */
internal suspend fun decodeZrle(source: VncSource, fb: VncFramebuffer, rect: VncRect, inflater: Inflater) {
    val lenBuf = ByteArray(4)
    source.readFully(lenBuf, 0, 4)
    val length = ((lenBuf[0].toInt() and 0xFF) shl 24) or ((lenBuf[1].toInt() and 0xFF) shl 16) or
        ((lenBuf[2].toInt() and 0xFF) shl 8) or (lenBuf[3].toInt() and 0xFF)
    if (length < 0) throw VncProtocolException("ZRLE length overflow")
    if (length > MAX_ZRLE_CHUNK) throw VncProtocolException("ZRLE chunk length $length exceeds max $MAX_ZRLE_CHUNK")
    val compressed = ByteArray(length)
    source.readFully(compressed, 0, length)
    val c = ByteCursor(inflater.inflate(compressed))

    var tileY = rect.y
    val endY = rect.y + rect.height
    while (tileY < endY) {
        val th = minOf(64, endY - tileY)
        var tileX = rect.x
        val endX = rect.x + rect.width
        while (tileX < endX) {
            val tw = minOf(64, endX - tileX)
            decodeZrleTile(c, fb, tileX, tileY, tw, th)
            tileX += 64
        }
        tileY += 64
    }
}

private fun decodeZrleTile(c: ByteCursor, fb: VncFramebuffer, tileX: Int, tileY: Int, tw: Int, th: Int) {
    val sub = c.u8()
    val rle = sub and 0x80 != 0
    val paletteSize = sub and 0x7F
    when {
        sub == 0 -> { // raw CPIXELs, raster order
            var i = 0
            val total = tw * th
            while (i < total) {
                fb.setPixel(tileX + i % tw, tileY + i / tw, c.cpixel())
                i++
            }
        }
        sub == 1 -> fb.fillRect(tileX, tileY, tw, th, c.cpixel()) // solid
        !rle && paletteSize in 2..16 -> { // packed palette
            val palette = IntArray(paletteSize) { c.cpixel() }
            val bpp = when {
                paletteSize == 2 -> 1
                paletteSize <= 4 -> 2
                else -> 4
            }
            val mask = (1 shl bpp) - 1
            var row = 0
            while (row < th) {
                var bitPos = 8 // force a fresh byte at the start of each (byte-aligned) row
                var current = 0
                var col = 0
                while (col < tw) {
                    if (bitPos >= 8) { current = c.u8(); bitPos = 0 }
                    val shift = 8 - bpp - bitPos
                    val index = (current ushr shift) and mask
                    fb.setPixel(tileX + col, tileY + row, palette[index.coerceIn(0, paletteSize - 1)])
                    bitPos += bpp
                    col++
                }
                row++
            }
        }
        rle && paletteSize == 0 -> { // plain RLE
            var done = 0
            val total = tw * th
            while (done < total) {
                val colour = c.cpixel()
                val len = c.runLength()
                var k = 0
                while (k < len && done < total) {
                    fb.setPixel(tileX + done % tw, tileY + done / tw, colour)
                    done++; k++
                }
            }
        }
        rle && paletteSize >= 1 -> { // palette RLE
            val palette = IntArray(paletteSize) { c.cpixel() }
            var done = 0
            val total = tw * th
            while (done < total) {
                val idx = c.u8()
                if (idx >= 128) {
                    val colour = palette[(idx - 128).coerceIn(0, paletteSize - 1)]
                    val len = c.runLength()
                    var k = 0
                    while (k < len && done < total) {
                        fb.setPixel(tileX + done % tw, tileY + done / tw, colour)
                        done++; k++
                    }
                } else {
                    fb.setPixel(tileX + done % tw, tileY + done / tw, palette[idx.coerceIn(0, paletteSize - 1)])
                    done++
                }
            }
        }
        else -> throw VncProtocolException("unsupported ZRLE subencoding $sub")
    }
}

/**
 * Tight (7): a compression-control byte selects Fill (one pixel), JPEG (platform-decoded), or Basic
 * (copy/palette/gradient filter, optionally zlib-compressed via one of four persistent streams).
 * The control byte's low 4 bits reset streams; its high nibble is the mode. Pixel data uses TPIXELs
 * (3 bytes for our 24-bit format). Data shorter than 12 bytes (uncompressed) is sent raw, not zlib.
 */
internal suspend fun decodeTight(
    source: VncSource,
    fb: VncFramebuffer,
    rect: VncRect,
    getStream: (Int) -> Inflater,
    resetStream: (Int) -> Unit,
    imageDecoder: VncImageDecoder?,
) {
    val one = ByteArray(1)
    suspend fun u8(): Int { source.readFully(one, 0, 1); return one[0].toInt() and 0xFF }
    suspend fun bytes(n: Int): ByteArray { val b = ByteArray(n); if (n > 0) source.readFully(b, 0, n); return b }
    // Tight compact length: up to 3 bytes, 7 bits each, high bit = continuation.
    suspend fun compactLen(): Int {
        var b = u8()
        var len = b and 0x7F
        if (b and 0x80 != 0) {
            b = u8(); len = len or ((b and 0x7F) shl 7)
            if (b and 0x80 != 0) { b = u8(); len = len or ((b and 0xFF) shl 14) }
        }
        return len
    }
    fun tpixel(src: ByteArray, off: Int): Int {
        val r = src[off].toInt() and 0xFF
        val g = src[off + 1].toInt() and 0xFF
        val b = src[off + 2].toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    val w = rect.width
    val h = rect.height
    if (w <= 0 || h <= 0) return

    val ctl = u8()
    for (i in 0 until 4) if (ctl and (1 shl i) != 0) resetStream(i)
    val mode = ctl shr 4

    when {
        mode == 0x08 -> { // Fill: one TPIXEL
            val p = bytes(3)
            fb.fillRect(rect.x, rect.y, w, h, tpixel(p, 0))
        }
        mode == 0x09 -> { // JPEG
            val len = compactLen()
            val jpeg = bytes(len)
            val decoder = imageDecoder ?: throw VncProtocolException("Tight JPEG needs an image decoder")
            val img = decoder.decodeToArgb(jpeg)
            var y = 0
            while (y < h && y < img.height) {
                var x = 0
                while (x < w && x < img.width) {
                    fb.setPixel(rect.x + x, rect.y + y, img.argb[y * img.width + x])
                    x++
                }
                y++
            }
        }
        mode < 0x08 -> { // Basic
            val streamId = mode and 0x03
            val explicitFilter = mode and 0x04 != 0
            val filterId = if (explicitFilter) u8() else 0
            when (filterId) {
                0 -> { // Copy: w*h TPIXELs
                    val rawSize = w * h * 3
                    val data = readTightData(source, rawSize, streamId, getStream, ::compactLen)
                    var di = 0
                    var y = 0
                    while (y < h) {
                        var x = 0
                        while (x < w) {
                            fb.setPixel(rect.x + x, rect.y + y, tpixel(data, di)); di += 3; x++
                        }
                        y++
                    }
                }
                1 -> { // Palette
                    val numColors = u8() + 1
                    val paletteBytes = bytes(numColors * 3)
                    val palette = IntArray(numColors) { tpixel(paletteBytes, it * 3) }
                    if (numColors <= 2) {
                        val rowSize = (w + 7) / 8
                        val data = readTightData(source, rowSize * h, streamId, getStream, ::compactLen)
                        var y = 0
                        while (y < h) {
                            var x = 0
                            while (x < w) {
                                val byte = data[y * rowSize + x / 8].toInt() and 0xFF
                                val bit = (byte ushr (7 - (x % 8))) and 1
                                fb.setPixel(rect.x + x, rect.y + y, palette[bit]); x++
                            }
                            y++
                        }
                    } else {
                        val data = readTightData(source, w * h, streamId, getStream, ::compactLen)
                        var i = 0
                        while (i < w * h) {
                            val idx = (data[i].toInt() and 0xFF).coerceIn(0, numColors - 1)
                            fb.setPixel(rect.x + i % w, rect.y + i / w, palette[idx]); i++
                        }
                    }
                }
                2 -> { // Gradient: per-channel prediction filter over raw RGB residuals
                    val data = readTightData(source, w * h * 3, streamId, getStream, ::compactLen)
                    val prev = IntArray(w * 3)
                    val cur = IntArray(w * 3)
                    var di = 0
                    var y = 0
                    while (y < h) {
                        var x = 0
                        while (x < w) {
                            var c = 0
                            while (c < 3) {
                                val left = if (x > 0) cur[(x - 1) * 3 + c] else 0
                                val up = if (y > 0) prev[x * 3 + c] else 0
                                val upLeft = if (x > 0 && y > 0) prev[(x - 1) * 3 + c] else 0
                                var pred = left + up - upLeft
                                if (pred < 0) pred = 0
                                if (pred > 255) pred = 255
                                cur[x * 3 + c] = (data[di++].toInt() + pred) and 0xFF
                                c++
                            }
                            val argb = (0xFF shl 24) or (cur[x * 3] shl 16) or (cur[x * 3 + 1] shl 8) or cur[x * 3 + 2]
                            fb.setPixel(rect.x + x, rect.y + y, argb); x++
                        }
                        cur.copyInto(prev)
                        y++
                    }
                }
                else -> throw VncProtocolException("unsupported Tight filter $filterId")
            }
        }
        else -> throw VncProtocolException("unsupported Tight mode $mode")
    }
}

/**
 * Read Tight Basic pixel data: raw when the uncompressed [rawSize] < 12 bytes, otherwise a
 * compact-length-prefixed zlib block through stream [streamId]. The [compactLen] reader is the one
 * bound to the current source.
 */
private suspend fun readTightData(
    source: VncSource,
    rawSize: Int,
    streamId: Int,
    getStream: (Int) -> Inflater,
    compactLen: suspend () -> Int,
): ByteArray {
    // rawSize is derived from the rect's w*h (bounded by RfbCodec.MAX_DIMENSION, so no Int overflow
    // upstream); a negative value could only come from a miscomputation — fail loudly, don't allocate.
    if (rawSize < 0) throw VncProtocolException("negative Tight data size $rawSize")
    if (rawSize < 12) {
        val b = ByteArray(rawSize)
        if (rawSize > 0) source.readFully(b, 0, rawSize)
        return b
    }
    val len = compactLen()
    val compressed = ByteArray(len)
    if (len > 0) source.readFully(compressed, 0, len)
    return getStream(streamId).inflate(compressed)
}
