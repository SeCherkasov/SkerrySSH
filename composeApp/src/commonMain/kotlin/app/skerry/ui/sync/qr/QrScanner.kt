package app.skerry.ui.sync.qr

import androidx.compose.runtime.Composable

/**
 * Whether this platform has a camera QR scanner. True only on Android (CameraX + ML Kit);
 * desktop has no scanner camera, so the pairing code is typed/pasted as text. UI hides the
 * "Scan QR" button where false.
 */
expect val qrScannerAvailable: Boolean

/**
 * Fullscreen camera QR scanner. On recognizing a code, passes the raw text to [onResult] (caller
 * runs it through [app.skerry.ui.sync.PairingPayload.decode]); [onCancel] fires on close without
 * a scan or denied camera access. Stub on desktop; only invoked when [qrScannerAvailable].
 */
@Composable
expect fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit)
