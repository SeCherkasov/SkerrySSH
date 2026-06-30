package app.skerry.ui.nav

import androidx.compose.runtime.Composable

// Desktop: системного «назад» нет — навигация внутри экранов идёт мышью/клавишами, перехватывать нечего.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
