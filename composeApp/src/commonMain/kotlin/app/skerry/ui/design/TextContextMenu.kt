package app.skerry.ui.design

import androidx.compose.runtime.Composable

/**
 * Draws the platform's text context menu — the one that opens on a right click over selected text or
 * a text field — in the app's own style.
 *
 * Desktop only in substance: there the menu is a Compose pop-up whose look is an application-level
 * choice, and Compose's default is a white Material sheet that has nothing to do with this UI. On
 * Android the menu is the system's floating toolbar, which belongs to the platform and is left alone.
 */
@Composable
expect fun SkerryTextContextMenu(content: @Composable () -> Unit)
