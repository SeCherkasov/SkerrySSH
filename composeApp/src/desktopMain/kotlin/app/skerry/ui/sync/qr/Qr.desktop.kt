package app.skerry.ui.sync.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * ZXing-генерация QR-матрицы на desktop (тот же чистый Java-API, что на Android). Низкоуровневый
 * [Encoder.encode] отдаёт сырую `ByteMatrix` модулей — тихую зону и масштаб добавляет [QrImage].
 */
actual fun encodeQrMatrix(text: String): QrMatrix? = runCatching {
    val qr = Encoder.encode(text, ErrorCorrectionLevel.M, null)
    val m = qr.matrix
    val side = m.width // для QR width == height
    QrMatrix(side, BooleanArray(side * m.height) { i -> m.get(i % side, i / side).toInt() == 1 })
}.getOrNull()
