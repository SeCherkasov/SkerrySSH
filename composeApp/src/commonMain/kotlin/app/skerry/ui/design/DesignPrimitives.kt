package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Базовый текст макета на UI-шрифте (Space Grotesk по умолчанию). Тонкая обёртка над
 * [BasicText], чтобы по всему воспроизведённому UI не дублировать [TextStyle]; для моноширинных
 * мест передаётся `font = LocalFonts.current.mono`.
 */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = D.text,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    font: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val family = font ?: LocalFonts.current.ui
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = family,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
        ),
    )
}

/** Бейдж-пилюля (STRICT/DEV/OWNER/…): фон + цвет текста + скругление 3dp. */
@Composable
fun Badge(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    radius: Int = 3,
    size: TextUnit = 9.sp,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius.dp))
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Txt(text, color = fg, size = size, weight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    }
}

/** Тег-чип (#prod, #docker): закруглённая пилюля 20dp. [active] — cyan-подсветка. */
@Composable
fun Chip(text: String, active: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) D.cyan.copy(alpha = 0.12f) else Color(0x0AFFFFFF))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Txt(
            text,
            color = if (active) D.cyanBright else D.dim,
            size = 10.5.sp,
            weight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** Переключатель 36×20 (как `tog`/`knob` прототипа): cyan при [on], белый кружок-кноб. */
@Composable
fun Toggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .width(36.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) D.cyan else D.whiteFaint)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
    ) {
        Box(
            Modifier
                .padding(start = if (on) 18.dp else 2.dp, top = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(D.white),
        )
    }
}

/** Горизонтальная линия-разделитель (1dp, cyan 6%). */
@Composable
fun HLine(color: Color = D.line, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Вертикальная линия-разделитель (1dp). */
@Composable
fun VLine(color: Color = D.lineStrong, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(color))
}

/** Маленькая статус-точка-кружок. */
@Composable
fun Dot(color: Color, size: Int = 6, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/**
 * Кнопка-иконка titlebar/toolbar: квадрат [box]dp, скруглённый. Иконка Material Symbols
 * размером [icon], цвет [tint].
 */
@Composable
fun IconBtn(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    box: Int = 28,
    icon: TextUnit = 18.sp,
    tint: Color = D.dim,
) {
    Box(
        modifier
            .size(box.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(name, size = icon, color = tint)
    }
}

/** Горизонтальный прогресс-бар (CPU/Memory/Disk/throughput): трек + цветная заливка [fraction]. */
@Composable
fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier, trackHeight: Int = 5) {
    Box(
        modifier
            .fillMaxWidth()
            .height(trackHeight.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x0FFFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color),
        )
    }
}

/** Заполненная primary-кнопка (cyan-фон, тёмный текст) с опциональной иконкой слева. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    bg: Color = D.cyan,
    fg: Color = Color(0xFF0A1A26),
) {
    Row(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Sym(icon, size = 16.sp, color = fg)
        Txt(label, color = fg, size = 12.sp, weight = FontWeight.SemiBold)
    }
}

/** Контурная (ghost) кнопка: прозрачный фон + cyan-граница. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    fg: Color = D.text,
    border: Color = D.lineStrong,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Sym(icon, size = 15.sp, color = fg)
        Txt(label, color = fg, size = 12.sp, weight = FontWeight.Medium)
    }
}
