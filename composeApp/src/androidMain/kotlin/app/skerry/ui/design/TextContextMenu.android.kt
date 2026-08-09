package app.skerry.ui.design

import androidx.compose.runtime.Composable

/** Android draws the system's own floating selection toolbar; nothing to restyle. */
@Composable
actual fun SkerryTextContextMenu(content: @Composable () -> Unit) {
    content()
}
