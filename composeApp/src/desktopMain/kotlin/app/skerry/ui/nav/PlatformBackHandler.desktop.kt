package app.skerry.ui.nav

import androidx.compose.runtime.Composable

// Desktop has no system back gesture; in-screen navigation is mouse/keyboard, nothing to intercept.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
