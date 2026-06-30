package app.skerry.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android: делегирует системному back через `OnBackPressedDispatcher` хост-Activity. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
