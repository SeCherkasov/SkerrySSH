package app.skerry.ui.sync.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * QR matrix generation via ZXing (same pure-Java API as Android). Low-level [Encoder.encode]
 * returns the raw module `ByteMatrix`; quiet zone and scaling are added by [QrImage].
 */
actual fun encodeQrMatrix(text: String): QrMatrix? = runCatching {
    val qr = Encoder.encode(text, ErrorCorrectionLevel.M, null)
    val m = qr.matrix
    val side = m.width // for a QR code width == height
    QrMatrix(side, BooleanArray(side * m.height) { i -> m.get(i % side, i / side).toInt() == 1 })
}.getOrNull()
