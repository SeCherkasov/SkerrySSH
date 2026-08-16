package app.skerry.ui.vnc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import app.skerry.shared.graphics.RemoteRect
import java.nio.ByteOrder
import java.nio.IntBuffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil

/**
 * Desktop pixel bridge (Skia). One long-lived [Bitmap] per desktop size, written through a direct
 * view of its own pixel memory: an ARGB `Int` stored little-endian is the byte order B,G,R,A —
 * exactly [ColorType.BGRA_8888] — so a dirty rectangle is a row-by-row `IntBuffer.put` into the
 * bitmap, and nothing is allocated or copied whole per frame. The previous shape of this class
 * rebuilt an 8 MB byte buffer, a fresh Skia bitmap and a fresh [ImageBitmap] for every applied
 * update, whole-desktop, even for a 32×16 rectangle (F-01).
 *
 * [bitmap] hands the draw a *new* wrapper only when pixels changed since the last read:
 * `notifyPixelsChanged` bumps the Skia generation (dropping any cached texture of the old pixels),
 * and the fresh wrapper defeats any caching keyed on the [ImageBitmap] instance itself.
 */
actual class FramebufferImage actual constructor(width: Int, height: Int) {

    private class Surface(width: Int, height: Int) {
        val bitmap = Bitmap().apply {
            allocPixels(
                ImageInfo(
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                    ColorType.BGRA_8888,
                    ColorAlphaType.PREMUL,
                ),
            )
        }
        val width get() = bitmap.width
        val height get() = bitmap.height

        // Valid for as long as [bitmap] is alive, which this object guarantees by holding it.
        val pixels: IntBuffer = run {
            val pixmap = bitmap.peekPixels() ?: error("a raster bitmap always exposes its pixels")
            BufferUtil.getByteBufferFromPointer(pixmap.addr, bitmap.width * bitmap.height * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer()
        }
    }

    // @Volatile for the same reason as RemoteFramebuffer's fields: writes happen on the session's read
    // loop (Dispatchers.Default) while the Compose draw thread reads them, and resize() swaps whole
    // objects that must publish safely. A torn dirty rect self-corrects on the next update.
    @Volatile
    private var surface = Surface(width, height)

    // Set when pixels change, so [bitmap] rewraps lazily (not on every recomposition).
    @Volatile
    private var dirty = true

    @Volatile
    private var cached: ImageBitmap? = null

    actual fun resize(width: Int, height: Int) {
        // Published through [dirty] alone: the getter re-reads [surface] after clearing the flag,
        // so a resize racing a read is picked up on this read or the next — never lost.
        surface = Surface(width, height)
        dirty = true
    }

    actual fun writeRects(rects: List<RemoteRect>, src: IntArray, srcWidth: Int) {
        val target = surface
        val pixels = target.pixels
        val capacity = target.width * target.height
        for (r in rects) {
            // A hostile server can declare right < left; a negative width passes the bounds sums
            // below and only blows up inside IntBuffer.put. Skip it, as the Android bridge does.
            if (r.width <= 0 || r.height <= 0) continue
            var row = 0
            while (row < r.height) {
                val srcOff = (r.y + row) * srcWidth + r.x
                val dstOff = (r.y + row) * target.width + r.x
                val inSource = srcOff >= 0 && srcOff + r.width <= src.size
                val inTarget = dstOff >= 0 && dstOff + r.width <= capacity
                if (inSource && inTarget) {
                    pixels.position(dstOff)
                    pixels.put(src, srcOff, r.width)
                }
                row++
            }
        }
        dirty = true
    }

    actual val bitmap: ImageBitmap
        get() {
            val current = cached
            if (!dirty && current != null) return current
            // Cleared BEFORE the rebuild: a write racing this getter re-raises the flag and merely
            // costs one extra rewrap. Clearing after would swallow that write's invalidation and
            // leave its pixels undrawn until the next unrelated update — forever, on an idle
            // desktop whose last frame lost the race.
            dirty = false
            val target = surface
            target.bitmap.notifyPixelsChanged()
            val image = target.bitmap.asComposeImageBitmap()
            cached = image
            return image
        }
}
