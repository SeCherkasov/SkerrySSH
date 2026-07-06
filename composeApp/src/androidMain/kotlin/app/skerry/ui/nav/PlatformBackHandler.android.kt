package app.skerry.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android: delegates to the system back gesture via the host Activity's `OnBackPressedDispatcher`. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
