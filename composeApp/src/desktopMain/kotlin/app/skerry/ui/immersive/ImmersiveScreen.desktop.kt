package app.skerry.ui.immersive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop has no system bars to hide — the window chrome is the app's own (undecorated window). */
actual fun applyPlatformImmersive(hidden: Boolean) = Unit

@Composable
actual fun Modifier.hiddenSystemBarsPadding(top: Boolean, bottom: Boolean): Modifier = this
