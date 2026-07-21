package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.AnnotatedString

/**
 * Base text on the UI font (Space Grotesk by default). Thin wrapper over [BasicText] so
 * [TextStyle] isn't duplicated across the UI; monospace call sites pass `font = LocalFonts.current.mono`.
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
    align: TextAlign = TextAlign.Unspecified,
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
            textAlign = align,
        ),
    )
}

/** [Txt] for pre-styled text (search-match highlighting): same defaults, spans of the string win. */
@Composable
fun Txt(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = D.text,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    font: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val family = font ?: LocalFonts.current.ui
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = family),
    )
}

/** Badge pill (STRICT/DEV/OWNER/…): background + text color + 3dp corner radius. */
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
        // Always single-line: wrapped text breaks the pill shape in tight rows.
        Txt(text, color = fg, size = size, weight = FontWeight.SemiBold, letterSpacing = 0.3.sp, maxLines = 1)
    }
}

/** Tag chip (#prod, #docker): rounded 20dp pill. [active] applies cyan highlight; [onClick] makes it clickable. */
@Composable
fun Chip(text: String, active: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) D.cyan.copy(alpha = 0.12f) else Color(0x0AFFFFFF))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
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

/**
 * Dropdown: [trigger] stays in the form's layout flow while [menu] floats above content via
 * [Popup] (doesn't push the form). The menu positions directly below the trigger ([gap] is the
 * offset) and receives the trigger's measured width to align edges. Tap-outside/Back call
 * [onDismiss]. Used by auth pickers (desktop/mobile) and other layout selects.
 */
@Composable
fun AnchoredDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    gap: Dp = 6.dp,
    // focusable = false for type-ahead pickers (group/tags): the menu must not steal focus from the
    // input field, or typing breaks. Those pickers close on field focus loss instead.
    focusable: Boolean = true,
    trigger: @Composable () -> Unit,
    menu: @Composable (anchorWidth: Dp) -> Unit,
) {
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    // Box wraps only the trigger (Popup doesn't participate in layout) so Box size equals trigger size.
    Box(Modifier.onGloballyPositioned { anchor = it.size }) {
        trigger()
        // Gated on anchor != Zero: before the first measurement the trigger size is unknown and the
        // Popup would flash one frame at (0,0). Wait for the first onGloballyPositioned.
        if (expanded && anchor != IntSize.Zero) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchor.height + with(density) { gap.roundToPx() }),
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = focusable),
            ) {
                menu(with(density) { anchor.width.toDp() })
            }
        }
    }
}

/** 36×20 toggle switch (prototype's `tog`/`knob`): cyan when [on], white knob circle. */
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

/** Horizontal divider line (1dp, cyan 6%). */
@Composable
fun HLine(color: Color = D.line, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Vertical divider line (1dp). */
@Composable
fun VLine(color: Color = D.lineStrong, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(color))
}

/** Small status dot. */
@Composable
fun Dot(color: Color, size: Int = 6, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/**
 * Titlebar/toolbar icon button: rounded [box]dp square. Material Symbols icon sized [icon], tinted
 * [tint]; [hoverTint] recolors the icon on hover (e.g. the window close cross turning white over
 * its red hover background), `null` keeps [tint].
 */
@Composable
fun IconBtn(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    box: Int = 28,
    icon: TextUnit = 18.sp,
    tint: Color = D.dim,
    hoverBg: Color = Color(0x1FFFFFFF),
    hoverTint: Color? = null,
) {
    // Custom light hover background (dark theme): clickable's default indication gives a dark
    // ripple that's barely visible on a dark background, so we highlight with a light overlay instead.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(box.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) hoverBg else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(name, size = icon, color = if (hovered && hoverTint != null) hoverTint else tint)
    }
}

/** Horizontal progress bar (CPU/Memory/Disk/throughput): track + colored fill for [fraction]. */
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

/**
 * Sparkline of a metric's recent history: a polyline over [values] with a translucent fill under
 * it, drawn right-aligned so the newest sample sits at the right edge and older ones scroll off.
 *
 * [values] are already normalized to 0..1 (the caller scales percentages or rates). A window
 * shorter than [capacity] is drawn against the full width scale, so the line grows in from the left
 * instead of stretching a two-sample history across the whole panel. Fewer than two points draw
 * nothing — a single dot reads as noise.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    capacity: Int = 60,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        fun pointAt(index: Int) = Offset(
            x = sparklineX(index, values.size, capacity, size.width),
            y = size.height * (1f - values[index].coerceIn(0f, 1f)),
        )
        val line = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(pointAt(values.lastIndex).x, size.height)
            lineTo(pointAt(0).x, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.14f))
        drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * X of sample [index] of [count] in a [Sparkline] of [width] px holding up to [capacity] samples:
 * samples sit one slot apart, right-aligned, so the newest is at the right edge and a partial
 * window grows in from the left rather than stretching across the whole width.
 */
internal fun sparklineX(index: Int, count: Int, capacity: Int, width: Float): Float {
    val step = width / (capacity.coerceAtLeast(2) - 1)
    return width - step * (count - 1 - index)
}

/** Filled primary button (cyan background, dark text) with an optional leading icon. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    bg: Color = D.cyan,
    fg: Color = D.ink,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Sym(icon, size = 16.sp, color = fg)
        Txt(label, color = fg, size = 12.sp, weight = FontWeight.SemiBold)
    }
}

/** Ghost button: transparent background + cyan border. */
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

/**
 * Compact numeric input: a "−  [field]  +" capsule with precise keyboard entry and button stepping.
 * Clamping/quantization is the caller's job — [onValueChange] receives a raw value it must range-clamp
 * itself (this primitive only edits the string and commits on Enter/blur/button). [format] renders the
 * number when unfocused, [parse] parses manual entry. Shared by desktop and mobile.
 */
@Composable
fun NumberStepper(
    value: Float,
    onValueChange: (Float) -> Unit,
    step: Float,
    format: (Float) -> String,
    parse: (String) -> Float?,
    modifier: Modifier = Modifier,
    suffix: String = "",
    fieldWidth: Dp = 46.dp,
) {
    val fonts = LocalFonts.current
    // While focused, show and edit the raw string [editing]; unfocused shows format(value).
    var editing by remember { mutableStateOf<String?>(null) }
    val shown = editing ?: format(value)
    val textStyle = remember(fonts.ui) { TextStyle(color = D.text, fontSize = 13.sp, fontFamily = fonts.ui, textAlign = TextAlign.Center) }
    fun commit() {
        editing?.let { e -> parse(e)?.let(onValueChange) }
        editing = null
    }
    Row(
        modifier.clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("remove") { onValueChange(value - step); editing = null }
        BasicTextField(
            value = shown,
            onValueChange = { editing = it },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(D.cyan),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.width(fieldWidth).onFocusChanged { if (!it.isFocused) commit() },
        )
        if (suffix.isNotEmpty()) Txt(suffix, color = D.faint, size = 12.sp)
        StepButton("add") { onValueChange(value + step); editing = null }
    }
}

/** Stepper's step button (−/+): a square tap target with a Material Symbols glyph. */
@Composable
private fun StepButton(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Sym(icon, size = 16.sp, color = D.faint)
    }
}
