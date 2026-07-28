package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpRect

/**
 * An offscreen surface of the graphics pipeline (MS-RDPEGFX 2.2.2.9).
 *
 * The server draws into surfaces and separately says where — if anywhere — each one appears on the
 * desktop. That indirection is the point of the pipeline: a surface can be prepared while it is
 * invisible, copied from another surface or restored from the cache, and only the mapping decides
 * what the user sees.
 */
class GraphicsSurface(val id: Int, val width: Int, val height: Int) {

    val pixels = IntArray(width * height)

    /** Where this surface sits on the desktop, or null while it is not mapped to the output. */
    var outputX: Int? = null
        private set

    var outputY: Int = 0
        private set

    fun mapToOutput(x: Int, y: Int) {
        outputX = x
        outputY = y
    }

    /** Drop everything drawn into the surface, keeping its identity and its mapping. */
    fun clear() = pixels.fill(0)

    /** [rect] clipped to the surface; an empty rectangle when it falls outside entirely. */
    fun clip(rect: RdpRect): RdpRect {
        val left = rect.x.coerceIn(0, width)
        val top = rect.y.coerceIn(0, height)
        val right = (rect.x + rect.width).coerceIn(0, width)
        val bottom = (rect.y + rect.height).coerceIn(0, height)
        return RdpRect(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
    }

    /** Copy [source] ([sourceWidth] wide) into the surface at ([x], [y]), clipped to the surface. */
    fun blit(x: Int, y: Int, sourceWidth: Int, sourceHeight: Int, source: IntArray) {
        val rect = clip(RdpRect(x, y, sourceWidth, sourceHeight))
        if (rect.width == 0 || rect.height == 0) return
        for (row in 0 until rect.height) {
            val sourceRow = (rect.y - y) + row
            val sourceOffset = sourceRow * sourceWidth + (rect.x - x)
            source.copyInto(
                pixels,
                destinationOffset = (rect.y + row) * width + rect.x,
                startIndex = sourceOffset,
                endIndex = sourceOffset + rect.width,
            )
        }
    }

    fun fill(rect: RdpRect, argb: Int) {
        val clipped = clip(rect)
        for (row in 0 until clipped.height) {
            val base = (clipped.y + row) * width + clipped.x
            pixels.fill(argb, base, base + clipped.width)
        }
    }

    /** The pixels of [rect] as a tightly packed image, for the cache and surface-to-surface copies. */
    fun read(rect: RdpRect): IntArray {
        val clipped = clip(rect)
        val out = IntArray(clipped.width * clipped.height)
        for (row in 0 until clipped.height) {
            val source = (clipped.y + row) * width + clipped.x
            pixels.copyInto(out, row * clipped.width, source, source + clipped.width)
        }
        return out
    }
}
