package app.skerry.ui.immersive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import app.skerry.ui.secure.SecureFlagController

/**
 * Hides or restores the platform's system bars. Android: status/navigation bars go away and come
 * back on a swipe from the edge; desktop: no-op (the window chrome is the app's own).
 */
expect fun applyPlatformImmersive(hidden: Boolean)

/**
 * Padding that keeps chrome clear of the system bars *as if they were visible*, for use inside an
 * [ImmersiveScreen]. Hidden bars report zero insets, so ordinary inset padding puts a bar flush
 * against the top edge — and the moment the user swipes the system bars back in temporarily, the
 * clock and status icons land on top of it. Reserving the space they would take avoids the overlap
 * for the price of a strip that is black anyway. Sides are always reserved (a landscape cutout);
 * [top] and [bottom] pick which bar's height to keep clear, depending on where the chrome sits.
 * Desktop: no-op.
 */
@Composable
expect fun Modifier.hiddenSystemBarsPadding(top: Boolean = true, bottom: Boolean = false): Modifier

// Reference-counted like the screenshot-protection flag ([SecureFlagController], whose counter this
// reuses): navigating from one full-bleed screen straight to another disposes the old effect around
// the same time the new one runs, and a plain hide/show pair would leave the bars up on a screen
// that asked for them to be gone.
private val immersiveController = SecureFlagController(::applyPlatformImmersive)

/**
 * Marks a screen as full-bleed: while this composable is in composition the system bars are hidden,
 * and they return when it leaves. Used by the session screens (VNC, terminal), where content shown
 * at anything less than the whole display wastes the scarcest thing on a phone — pixels — and where
 * the status bar sat on top of the picture in landscape.
 */
@Composable
fun ImmersiveScreen() {
    DisposableEffect(Unit) {
        immersiveController.acquire()
        onDispose { immersiveController.release() }
    }
}
