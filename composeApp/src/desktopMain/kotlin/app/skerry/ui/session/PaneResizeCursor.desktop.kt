package app.skerry.ui.session

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

// Built once: a PointerIcon is immutable and hovering a divider must not allocate an AWT cursor per
// frame. Same predefined cursors the window's own resize edges use (see WindowFrame).
private val COLUMN_RESIZE = PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
private val ROW_RESIZE = PointerIcon(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR))

actual fun paneResizeCursor(vertical: Boolean): PointerIcon = if (vertical) COLUMN_RESIZE else ROW_RESIZE
