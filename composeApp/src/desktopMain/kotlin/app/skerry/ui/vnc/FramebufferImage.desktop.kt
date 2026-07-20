package app.skerry.ui.vnc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import app.skerry.shared.vnc.VncRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Desktop pixel bridge (Skia). Keeps an ARGB `IntArray` mirror and re-installs it into a Skia
 * [Bitmap] when [bitmap] is read. An ARGB `Int` (0xAARRGGBB) laid out little-endian is the byte
 * order B,G,R,A — exactly [ColorType.BGRA_8888] — so the copy is a straight int→byte reinterpret.
 */
actual class FramebufferImage actual constructor(width: Int, height: Int) {
    // @Volatile for the same reason as VncFramebuffer's fields: writes happen on the session's read
    // loop (Dispatchers.Default) while the Compose draw thread reads them, and resize() swaps whole
    // objects that must publish safely. A torn dirty rect self-corrects on the next update.
    @Volatile
    private var w = width

    @Volatile
    private var h = height

    @Volatile
    private var pixels = IntArray(width * height)

    // Set when pixels change, so [bitmap] rebuilds the Skia image lazily (not on every recomposition).
    @Volatile
    private var dirty = true

    @Volatile
    private var cached: ImageBitmap? = null

    actual fun resize(width: Int, height: Int) {
        w = width
        h = height
        pixels = IntArray(width * height)
        dirty = true
    }

    actual fun writeRects(rects: List<VncRect>, src: IntArray, srcWidth: Int) {
        for (r in rects) {
            var row = 0
            while (row < r.height) {
                val srcOff = (r.y + row) * srcWidth + r.x
                val dstOff = (r.y + row) * w + r.x
                if (srcOff >= 0 && dstOff >= 0 && dstOff + r.width <= pixels.size && srcOff + r.width <= src.size) {
                    src.copyInto(pixels, dstOff, srcOff, srcOff + r.width)
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
            val bmp = Bitmap()
            bmp.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
            val bytes = ByteArray(w * h * 4)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (p in pixels) bb.putInt(p)
            bmp.installPixels(bytes)
            val image = bmp.asComposeImageBitmap()
            cached = image
            dirty = false
            return image
        }
}
