package app.skerry.shared.vnc

/**
 * The remote screen's pixel buffer: ARGB_8888, row-major, `pixels[y * width + x]`. Owned by the
 * decoder ([RfbCodec]) — the read loop mutates it in place as framebuffer updates arrive, then the
 * session emits a [VncUpdate.Region] naming the changed rectangles so the UI uploads only those.
 *
 * The UI layer READS the pixels (to wrap them in a platform bitmap) but never writes them. The
 * buffer is deliberately platform-neutral (`IntArray`, no Compose `ImageBitmap`) so it lives in
 * `commonMain`; the pixel→bitmap bridge is an expect/actual on the UI side.
 *
 * Not thread-safe by construction: all mutation happens on the single read-loop coroutine, and the
 * UI reads after the loop has emitted the region for the frame. [pixels]/[width]/[height] are
 * `@Volatile` so a [resize] (which swaps the array) publishes safely to the reader; a one-pixel tear
 * on a dirty rect is harmless and self-corrects on the next update (same reasoning as the
 * `@Volatile` window size in `TelnetCodec`).
 */
class VncFramebuffer(width: Int, height: Int) {
    @Volatile
    var width: Int = width
        private set

    @Volatile
    var height: Int = height
        private set

    @Volatile
    var pixels: IntArray = IntArray(width * height)
        private set

    /** Reallocate for a new desktop size (server resize / DesktopSize pseudo-encoding). Contents are cleared. */
    fun resize(newWidth: Int, newHeight: Int) {
        require(newWidth >= 0 && newHeight >= 0) { "negative framebuffer size ${newWidth}x$newHeight" }
        width = newWidth
        height = newHeight
        pixels = IntArray(newWidth * newHeight)
    }

    /** Set a single ARGB pixel. Out-of-bounds coordinates are ignored (defensive against malformed rects). */
    fun setPixel(x: Int, y: Int, argb: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = argb
    }

    /**
     * Copy [w] ARGB pixels from [src] (starting at [srcOffset]) into the row at ([x], [y]). Used by
     * decoders that produce whole rows/tiles (Raw, Hextile, ZRLE, Tight). Clipped to the buffer
     * width so a rect that overruns the right edge doesn't corrupt the next row.
     */
    fun blitRow(x: Int, y: Int, w: Int, src: IntArray, srcOffset: Int) {
        if (y < 0 || y >= height || w <= 0) return
        val startX = x.coerceAtLeast(0)
        val endX = (x + w).coerceAtMost(width)
        if (endX <= startX) return
        val skipped = startX - x
        src.copyInto(
            pixels,
            destinationOffset = y * width + startX,
            startIndex = srcOffset + skipped,
            endIndex = srcOffset + skipped + (endX - startX),
        )
    }

    /**
     * Fill the [w]×[h] rectangle at ([x], [y]) with a solid ARGB colour (Hextile background/
     * foreground subrects, Tight Fill). Clipped to the buffer.
     */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val x0 = x.coerceAtLeast(0)
        val y0 = y.coerceAtLeast(0)
        val x1 = (x + w).coerceAtMost(width)
        val y1 = (y + h).coerceAtMost(height)
        var yy = y0
        while (yy < y1) {
            val base = yy * width
            var xx = x0
            while (xx < x1) {
                pixels[base + xx] = argb
                xx++
            }
            yy++
        }
    }

    /**
     * Move a [w]×[h] block from ([srcX], [srcY]) to ([dstX], [dstY]) within the buffer (RFB
     * CopyRect). Overlap-safe: rows are copied top-down or bottom-up depending on vertical direction
     * so a downward copy doesn't overwrite source rows it still needs. Clipped to the buffer.
     */
    fun copyRect(srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Clip the moved region to what fits at BOTH source and destination.
        val cw = minOf(w, width - srcX, width - dstX)
        val ch = minOf(h, height - srcY, height - dstY)
        if (cw <= 0 || ch <= 0) return
        val topDown = dstY <= srcY
        var row = if (topDown) 0 else ch - 1
        val step = if (topDown) 1 else -1
        var remaining = ch
        while (remaining > 0) {
            pixels.copyInto(
                pixels,
                destinationOffset = (dstY + row) * width + dstX,
                startIndex = (srcY + row) * width + srcX,
                endIndex = (srcY + row) * width + srcX + cw,
            )
            row += step
            remaining--
        }
    }
}
