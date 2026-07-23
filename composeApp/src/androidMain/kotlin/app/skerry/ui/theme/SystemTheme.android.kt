package app.skerry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * On Android `isSystemInDarkTheme()` already recomposes on the uiMode configuration change and
 * costs nothing to keep composed, so [enabled] is ignored — it only gates the desktop OS watcher.
 */
@Composable
actual fun systemInDarkTheme(enabled: Boolean): Boolean = isSystemInDarkTheme()
