package app.skerry.ui.sync.qr

import androidx.compose.runtime.Composable

/** Desktop: камеры-сканера нет — код связывания вводится/вставляется текстом. */
actual val qrScannerAvailable: Boolean = false

/** Никогда не вызывается (UI прячет кнопку «Scan» при [qrScannerAvailable] == false); no-op заглушка. */
@Composable
actual fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
}
