package app.skerry.ui.session

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The cursor shown over a divider between panes: the arrow that says "this can be dragged", pointing
 * along the axis the divider moves on ([vertical] = a divider between panes side by side, so the
 * cursor points left/right).
 *
 * Platform-specific because resize cursors are not part of the common [PointerIcon] set: the desktop
 * takes them from AWT, Android has no pointer to shape them for and keeps the default.
 */
expect fun paneResizeCursor(vertical: Boolean): PointerIcon
