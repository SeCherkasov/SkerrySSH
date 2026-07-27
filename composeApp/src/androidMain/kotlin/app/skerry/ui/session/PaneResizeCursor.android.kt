package app.skerry.ui.session

import androidx.compose.ui.input.pointer.PointerIcon

/** Android draws no pointer to reshape (and has no pane grid of its own), so the default stands. */
actual fun paneResizeCursor(vertical: Boolean): PointerIcon = PointerIcon.Default
