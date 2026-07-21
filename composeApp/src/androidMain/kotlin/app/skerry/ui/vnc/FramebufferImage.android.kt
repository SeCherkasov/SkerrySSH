package app.skerry.ui.vnc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.skerry.shared.vnc.VncRect

/**
 * Android pixel bridge (android.graphics). `Bitmap.setPixels` takes ARGB `Int`s directly (the
 * codec's format), so each dirty rect uploads straight from the shared framebuffer with no
 * conversion.
 */
actual class FramebufferImage actual constructor(width: Int, height: Int) {
    // @Volatile: resize() swaps the Bitmap on the session's read loop (Dispatchers.Default) while the
    // Compose draw thread reads it — the new instance must publish safely. Same reasoning as
    // VncFramebuffer's fields.
    @Volatile
    private var bmp = createBitmap(width, height)

    private fun createBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    actual fun resize(width: Int, height: Int) {
        bmp = createBitmap(width, height)
    }

    actual fun writeRects(rects: List<VncRect>, src: IntArray, srcWidth: Int) {
        val bw = bmp.width
        val bh = bmp.height
        for (r in rects) {
            val x = r.x.coerceIn(0, bw)
            val y = r.y.coerceIn(0, bh)
            val rw = minOf(r.width, bw - x, srcWidth - x)
            val rh = minOf(r.height, bh - y)
            if (rw <= 0 || rh <= 0) continue
            bmp.setPixels(src, y * srcWidth + x, srcWidth, x, y, rw, rh)
        }
    }

    actual val bitmap: ImageBitmap
        get() = bmp.asImageBitmap()
}
