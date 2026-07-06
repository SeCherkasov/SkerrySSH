package app.skerry.ui.sync.qr

import androidx.compose.runtime.Composable

/** Desktop has no camera scanner; the pairing code is typed/pasted as text. */
actual val qrScannerAvailable: Boolean = false

/** Never invoked (UI hides the Scan button when [qrScannerAvailable] is false); no-op stub. */
@Composable
actual fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
}
