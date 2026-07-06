package app.skerry.ui.sync.qr

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Black-and-white QR matrix: [side]×[side] modules, `modules[y * side + x] == true` means a dark
 * module. Excludes the quiet zone (added by the renderer) — these are the raw code modules.
 * Produced by [encodeQrMatrix].
 */
class QrMatrix(val side: Int, val modules: BooleanArray)

/**
 * Encodes [text] into a QR matrix (ZXing, error-correction level M). Returns null if the text
 * doesn't fit or the codec throws. Platform-specific implementation; ZXing core is available on
 * both desktop JVM and Android.
 */
expect fun encodeQrMatrix(text: String): QrMatrix?

/**
 * Draws a QR code for [text] onto a [Canvas]. Dark modules use [dark] on a [light] background
 * with a 4-module quiet zone (required by the standard for reliable scanning). Light background
 * is deliberate: cameras read dark-on-light more reliably than inverted, even in dark theme. The
 * matrix is cached by [text]. If encoding fails, draws an empty light square (caller shows the
 * text code alongside).
 */
@Composable
fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
    dark: Color = Color(0xFF07141E),
    light: Color = Color(0xFFF4F8FA),
) {
    val matrix = remember(text) { encodeQrMatrix(text) }
    Canvas(modifier) {
        drawRect(light) // background plus quiet zone
        if (matrix == null || matrix.side == 0) return@Canvas
        val quiet = 4
        val total = matrix.side + quiet * 2
        val cell = size.minDimension / total
        for (y in 0 until matrix.side) {
            for (x in 0 until matrix.side) {
                if (matrix.modules[y * matrix.side + x]) {
                    drawRect(
                        color = dark,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
