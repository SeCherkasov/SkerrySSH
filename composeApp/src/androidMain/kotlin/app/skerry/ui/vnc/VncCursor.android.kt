package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalContext

/**
 * Touch draws no pointer, so this matters only when a mouse is attached (DeX, a tablet with a
 * trackpad) — there Android shows a system pointer over the framebuffer just like desktop does.
 * TYPE_NULL is the platform's own "no pointer" icon.
 */
@Composable
internal actual fun hiddenPointerIcon(): PointerIcon? {
    val context = LocalContext.current
    return remember(context) {
        PointerIcon(
            android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL),
        )
    }
}
