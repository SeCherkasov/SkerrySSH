package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Whether the local system pointer should be hidden.
 *
 * We don't advertise the Cursor pseudo-encoding, so the server draws the remote cursor INTO the
 * framebuffer — leaving the OS pointer on top of it means the user sees two cursors. Hiding ours
 * makes the remote one the only pointer, which is what it already tracks.
 *
 * Only where a remote cursor actually exists, hence all three conditions:
 * - [pointerOverImage]: the fitted image, NOT the whole tab. Over the letterbox around it (or a tab
 *   with no frame yet) nothing tracks the mouse, so hiding ours would leave no pointer at all.
 * - not [viewOnly]: there we send no pointer events, so the remote cursor doesn't follow the mouse.
 * - [interactive]: a frozen last frame after a drop tracks nothing either.
 */
fun shouldHideLocalCursor(interactive: Boolean, viewOnly: Boolean, pointerOverImage: Boolean): Boolean =
    interactive && !viewOnly && pointerOverImage

/**
 * A fully transparent pointer icon, or `null` where the platform has no pointer to hide. Platform
 * split because a blank cursor is an AWT image on desktop and a system icon type on Android.
 */
@Composable
internal expect fun hiddenPointerIcon(): PointerIcon?
