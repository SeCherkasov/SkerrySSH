package app.skerry.ui.remote

import androidx.compose.ui.unit.IntSize

/**
 * A drawing surface: its size in **physical** pixels and the display scaling those pixels are drawn
 * at (1.0 = 100%).
 *
 * One value rather than two arguments because the two only mean anything together — a size paired
 * with the scaling of a different display describes a DPI neither of them has, and that is exactly
 * what a remote desktop is laid out from (see [app.skerry.shared.rdp.RdpDisplayScale]).
 */
data class RemoteViewport(val size: IntSize, val scale: Float) {
    companion object {
        /** No surface reported yet. */
        val None = RemoteViewport(IntSize.Zero, 1f)
    }
}
