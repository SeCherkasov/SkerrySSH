package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import app.skerry.shared.vnc.VncRect
import app.skerry.shared.vnc.VncUpdate

/**
 * The remote cursor sprite from the Cursor pseudo-encoding, ready to draw. Backed by
 * [FramebufferImage] because it is the same problem the framebuffer already solved — turning the
 * codec's raw ARGB into a platform [ImageBitmap] — only at sprite size and built once per shape.
 */
@Stable
class VncCursorImage private constructor(
    private val image: FramebufferImage,
    val width: Int,
    val height: Int,
    val hotspotX: Int,
    val hotspotY: Int,
) {
    val bitmap: ImageBitmap get() = image.bitmap

    companion object {
        /** Build a sprite from a decoded [shape], or null for the server's "no cursor" (a 0×0 shape). */
        fun of(shape: VncUpdate.CursorShape): VncCursorImage? {
            if (shape.width <= 0 || shape.height <= 0) return null
            val image = FramebufferImage(shape.width, shape.height)
            image.writeRects(listOf(VncRect(0, 0, shape.width, shape.height)), shape.argb, shape.width)
            return VncCursorImage(image, shape.width, shape.height, shape.hotspotX, shape.hotspotY)
        }
    }
}

/**
 * Whether the local system pointer should be hidden.
 *
 * While we draw the remote cursor ourselves (the Cursor pseudo-encoding — see
 * [app.skerry.shared.vnc.VncSession.setLocalCursor]) the OS pointer on top of it would be a second
 * cursor; the same is true of a server that ignores the encoding and paints the cursor into the
 * framebuffer instead. Either way something already tracks the mouse, so ours goes away.
 *
 * Only where a remote cursor actually exists, hence all three conditions:
 * - [pointerOverImage]: the fitted image, NOT the whole tab. Over the letterbox around it (or a tab
 *   with no frame yet) nothing tracks the mouse, so hiding ours would leave no pointer at all.
 * - not [viewOnly]: there we send no pointer events, so nothing follows the mouse — the server paints
 *   the cursor wherever it really is instead, and our own pointer is just our own pointer.
 * - [interactive]: a frozen last frame after a drop tracks nothing either.
 */
fun shouldHideLocalCursor(interactive: Boolean, viewOnly: Boolean, pointerOverImage: Boolean): Boolean =
    interactive && !viewOnly && pointerOverImage

/**
 * Where to draw the cursor sprite's top-left corner, in canvas coordinates, for a pointer at
 * ([pointerX], [pointerY]). Null when the pointer is outside the drawn image.
 *
 * The pointer is snapped to the framebuffer pixel it maps to before the hotspot is subtracted, so
 * the sprite lands exactly where the remote pointer is — the same rounding the server applies to the
 * pointer events we send it — instead of drifting up to a pixel away under zoom.
 */
fun cursorTopLeft(geom: FitGeometry, pointerX: Float, pointerY: Float, hotspotX: Int, hotspotY: Int): Offset? {
    val fb = geom.toFramebuffer(pointerX, pointerY) ?: return null
    return Offset(
        geom.offsetX + (fb.x - hotspotX) * geom.scale,
        geom.offsetY + (fb.y - hotspotY) * geom.scale,
    )
}

/**
 * A fully transparent pointer icon, or `null` where the platform has no pointer to hide. Platform
 * split because a blank cursor is an AWT image on desktop and a system icon type on Android.
 */
@Composable
internal expect fun hiddenPointerIcon(): PointerIcon?
