package app.skerry.ui.sync.qr

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Чёрно-белая матрица QR-кода: [side]×[side] модулей, `modules[y * side + x] == true` ⇒ тёмный модуль.
 * Без «тихой зоны» (её добавляет рендер) — это сырые модули кода. Генерится из [encodeQrMatrix].
 */
class QrMatrix(val side: Int, val modules: BooleanArray)

/**
 * Закодировать [text] в QR-матрицу (ZXing, уровень коррекции M). `null`, если текст не помещается в QR
 * или кодек бросил. Реализация платформенная — ZXing core доступен и на desktop JVM, и на Android.
 */
expect fun encodeQrMatrix(text: String): QrMatrix?

/**
 * Нарисовать QR-код [text] на [Canvas]. Тёмные модули — [dark] на фоне [light] с «тихой зоной» вокруг
 * (4 модуля, требование стандарта для надёжного скана). Светлый фон осознанно: камеры читают
 * тёмное-на-светлом надёжнее инверсии, даже в тёмной теме приложения. Матрица кэшируется по [text].
 * Если код не закодировался — рисуем пустой светлый квадрат (вызывающий покажет текстовый код рядом).
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
        drawRect(light) // фон + тихая зона
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
