package app.skerry.ui.vnc

import app.skerry.shared.graphics.RemoteFramebuffer
import androidx.compose.ui.graphics.ImageBitmap
import app.skerry.shared.graphics.RemoteRect

/**
 * Platform bridge from the codec's raw ARGB [app.skerry.shared.vnc.RemoteFramebuffer] pixels to a
 * Compose [ImageBitmap] the UI can draw. It's `expect`/`actual` because Compose Multiplatform is
 * Skia-backed on desktop and android.graphics-backed on Android, with no common raw-pixel writer.
 *
 * [writeRects] uploads only the changed rectangles (from the shared framebuffer's `IntArray`),
 * matching the codec's dirty-rect model so a full-screen copy isn't done every frame.
 *
 * [straightAlpha] declares the source ints straight (non-premultiplied) ARGB — the cursor-sprite
 * contract ([app.skerry.shared.graphics.RemoteDesktopUpdate.CursorShape]) — and the platform whose
 * bitmap wants premultiplied pixels (desktop/Skia) converts on write. The default is the
 * framebuffer path: always opaque, where the two models coincide and the write stays a raw copy.
 */
expect class FramebufferImage(width: Int, height: Int, straightAlpha: Boolean = false) {
    /** Reallocate for a new desktop size (server resize). */
    fun resize(width: Int, height: Int)

    /**
     * Copy the given [rects] from [src] (a row-major ARGB buffer [srcWidth] px wide — the shared
     * framebuffer's pixels) into the platform bitmap.
     */
    fun writeRects(rects: List<RemoteRect>, src: IntArray, srcWidth: Int)

    /** The current image for drawing. May be re-created on [resize]. */
    val bitmap: ImageBitmap
}
