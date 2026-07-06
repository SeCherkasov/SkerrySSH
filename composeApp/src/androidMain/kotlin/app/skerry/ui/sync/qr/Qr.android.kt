package app.skerry.ui.sync.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * ZXing QR matrix generation on Android. The low-level [Encoder.encode] returns a raw module
 * `ByteMatrix` (no scaling or quiet zone, added by [QrImage]) instead of `QRCodeWriter`, which
 * rasterizes directly to a pixel size.
 */
actual fun encodeQrMatrix(text: String): QrMatrix? = runCatching {
    val qr = Encoder.encode(text, ErrorCorrectionLevel.M, null)
    val m = qr.matrix
    val side = m.width // QR codes are square: width == height
    QrMatrix(side, BooleanArray(side * m.height) { i -> m.get(i % side, i / side).toInt() == 1 })
}.getOrNull()
