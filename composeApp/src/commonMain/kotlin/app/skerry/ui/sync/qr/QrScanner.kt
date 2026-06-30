package app.skerry.ui.sync.qr

import androidx.compose.runtime.Composable

/**
 * Доступен ли на этой платформе сканер QR камерой. `true` только на Android (CameraX + ML Kit);
 * на desktop камеры-сканера нет — код связывания вводится/вставляется текстом. UI прячет кнопку
 * «Scan QR» там, где `false`.
 */
expect val qrScannerAvailable: Boolean

/**
 * Полноэкранный сканер QR камерой. Распознав код, отдаёт сырой текст в [onResult] (вызывающий
 * прогоняет его через [app.skerry.ui.sync.PairingPayload.decode]); [onCancel] — закрытие без скана
 * или отказ в доступе к камере. На desktop — заглушка (вызывается только при [qrScannerAvailable]).
 */
@Composable
expect fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit)
