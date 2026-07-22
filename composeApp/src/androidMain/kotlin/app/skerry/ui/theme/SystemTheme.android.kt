package app.skerry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** On Android `isSystemInDarkTheme()` already recomposes on the uiMode configuration change. */
@Composable
actual fun systemInDarkTheme(): Boolean = isSystemInDarkTheme()
