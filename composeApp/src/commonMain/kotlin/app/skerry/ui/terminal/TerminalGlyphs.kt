package app.skerry.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.UnderlineStyle
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.highlight.HighlightKind
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.terminal_copied
import app.skerry.ui.theme.Skerry

/**
 * Interactive terminal: renders [TerminalScreenState.screen] (cell grid with color/weight from
 * [app.skerry.shared.terminal.TerminalEmulator]) and a block cursor at the cursor position. This is
 * the input focus target; keystrokes go to the PTY per-character ([mapTerminalKey]) and the shell
 * draws its own echo. There is no command line under the terminal; the bottom row is reserved for
 * the AI assistant.
 *
 * Selection: on mouse, drag directly extends a linear range ([TerminalSelection]) over the grid
 * (single click clears selection and returns focus); on touch, plain drag scrolls and selection
 * starts on long-press. The range is highlighted with translucent cyan. Copy via `Ctrl+Shift+C`
 * (desktop) or the system text toolbar's "Copy" menu that appears after touch selection
 * ([LocalTextToolbar]). Typing clears both selection and menu.
 *
 * [imeInput] enables the mobile input path: the soft keyboard does not send key events to
 * [onPreviewKeyEvent], so input is captured from a hidden `BasicTextField` ([imeDeltaToPty]).
 * Desktop keeps this `false`, relying on the physical keyboard via [mapTerminalKey].
 *
 * [imeTransform] (IME path only) post-processes a non-empty [imeDeltaToPty] result before sending —
 * the mobile key panel routes it through sticky-ctrl ([app.skerry.ui.mobile.applyStickyCtrl]) so
 * Ctrl+<letter> works from the soft keyboard too, not just from panel keys.
 *
 * [fixedGrid] pins the grid to a given size and scales the font to fill the viewport instead of
 * fitting the grid to the viewport. It exists for the recording player: a recording was taken at a
 * geometry of its own, and re-flowing it would leave empty columns in a wide pane and wrap its
 * lines in a narrow one. A live session leaves this `null`.
 */
/**
 * Cell size of [style], measured with [measurer].
 *
 * cellWidth is the font's real advance, which drawText uses to lay out ASCII runs. Measured on a
 * long string and divided by its length: size.width is an integer (rounds to ~0.5px), which at 10
 * chars gave error up to ~0.05px/char and drifted the ASCII run off the cw-grid by ~1 cell near the
 * row's right edge (highlight/cursor/mouse use the grid, text uses advance). At 200 chars the error
 * is negligible and the grid matches drawText's layout.
 *
 * cellHeight is rounded to a whole pixel: rows tile edge-to-edge (top = r*cellHeight), and a
 * fractional height (e.g. line-height multiplier 1.38 → 13×1.38 = 17.94px, or fractional display
 * scale) puts adjacent background rects' borders on fractional pixels — Skia antialiases the seam,
 * showing horizontal banding every row on solid backgrounds (e.g. mc panels). Integer height removes
 * the seam. Width cannot be rounded the same way (its fractional advance is intentional, see above),
 * but height can: each row's text is drawn independent of its top, so no drift accumulates.
 */
internal fun terminalMetrics(measurer: TextMeasurer, style: TextStyle, density: Density): TerminalMetrics {
    val sampleLen = 200
    val sample = measurer.measure(AnnotatedString("M".repeat(sampleLen)), style)
    return TerminalMetrics(
        cellWidth = sample.size.width / sampleLen.toFloat(),
        cellHeight = with(density) { style.lineHeight.toPx() }.roundToInt().toFloat(),
    )
}

/** A file path under the pointer while Ctrl is held: [row] in the grid, [span] in columns. */
internal data class HoveredPath(val row: Int, val span: TextLinkSpan)

/**
 * Brief, self-dismissing "Copied" banner. [nonce] is bumped on each successful copy; 0 means "never
 * copied yet / reset on tab switch" and hides it ([shouldShowCopiedFlash]). Each bump fades the banner
 * in and hides it after [COPIED_BANNER_MS]; a re-key to 0 mid-show hides it immediately (no stuck pill).
 */
@Composable
internal fun CopiedBanner(nonce: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(nonce) {
        if (!shouldShowCopiedFlash(nonce)) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        delay(COPIED_BANNER_MS)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        TerminalOverlayBanner(
            icon = "content_copy",
            text = stringResource(Res.string.terminal_copied),
            accent = Skerry.colors.cyan,
            background = Skerry.colors.surfaceDeep.copy(alpha = 0.8f),
            contentColor = Skerry.colors.cyanBright,
        )
    }
}

/**
 * Draws one touch selection handle: a vertical "stem" along the cell edge (a row high) and a "drop"
 * circle below the anchor, offset outward from the text (start — left, end — right), like system
 * selection handles. [anchor] — the corner point of the edge in canvas coordinates.
 */
internal fun DrawScope.drawSelectionHandle(
    anchor: Offset,
    radius: Float,
    cellHeight: Float,
    which: SelectionHandle,
    handleColor: Color,
) {
    drawLine(
        color = handleColor,
        start = Offset(anchor.x, anchor.y - cellHeight),
        end = anchor,
        strokeWidth = radius * 0.5f,
    )
    val cx = anchor.x + if (which == SelectionHandle.START) -radius else radius
    drawCircle(color = handleColor, radius = radius, center = Offset(cx, anchor.y + radius))
}

/**
 * Draws a terminal glyph run: explicit measure + drawing with a color override on top of the layout.
 * The `drawText(measurer, text, style = …)` overload can't be used: the [TextMeasurer] cache compares
 * styles only by layout attributes (color isn't in the key), and that overload paints with the color
 * baked into the cached layout — on a terminal theme change the whole screen stayed in the old palette
 * until the screen was recreated (tab switch), and identical text of two different colors in one frame
 * would be painted with the first's color. Here color is passed at draw time ([drawText] over a ready
 * [androidx.compose.ui.text.TextLayoutResult] overrides it), so a cache hit is safe; the cache key also
 * doesn't depend on the column (constraints aren't derived from topLeft by default) — identical glyphs
 * in different columns share one layout.
 */
internal fun DrawScope.drawGlyphText(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    val layout = measurer.measure(AnnotatedString(text), style, density = this, layoutDirection = layoutDirection)
    drawText(layout, color = style.color, topLeft = topLeft)
}

/**
 * Cell background color for the per-cell overlay (fill across the full row width, including trailing
 * spaces). inverse → the text color (reverse-video swaps fg/bg); an explicit bg → its color; default
 * background without inverse → `null` (nothing to draw — the shared terminal background shows). The
 * selection highlight is applied by the overlay in a separate layer over the background.
 */
internal fun cellBgColor(style: TermStyle, palette: Palette, theme: TerminalTheme): Color? = when {
    style.inverse -> style.fg.toComposeColor(theme, palette)
    style.bg == TermColor.Default -> null
    else -> style.bg.toComposeColor(theme, palette)
}

/**
 * One glyph run to draw: text, start column [col], number of columns occupied [span] (for underline:
 * Wide=2, ASCII run = cell count), style, and the client-side highlight category over it ([kind]).
 *
 * [kind] stays separate from [style] so the run keeps the server's own attributes for the underline
 * pass — the highlight recolors the glyph, not the line under it.
 */
internal data class GlyphRun(
    val col: Int,
    val text: String,
    val span: Int,
    val style: TermStyle,
    val kind: HighlightKind = HighlightKind.None,
)

/**
 * Printable ASCII (a single BMP char 0x20..0x7e) — in JetBrains Mono guaranteed a cellWidth advance.
 * Called only for Single cells (Continuation/Wide are filtered earlier in [glyphRuns]).
 */
private fun TermCell.isPlainAscii(): Boolean = text.length == 1 && text[0].code in 0x20..0x7e

/**
 * Segments a grid row into glyph runs. Consecutive same-style ASCII cells are merged into one run (the
 * fast monospace drawText), while each non-ASCII glyph (mc box-drawing, CJK, symbols) is split into its
 * own one-column run — because a fallback font draws such glyphs with advance ≠ cellWidth, and in a long
 * run that accumulates drift (ragged box horizontals, colored rows sliding by a column). A wide cell — a
 * separate two-column run; a Continuation carries no glyph. The run's column is the physical cell index,
 * so a Continuation "hole" doesn't shift the next run.
 *
 * [highlight] adds the client's own syntax categories: a run also breaks where the category changes,
 * so a token boundary inside otherwise identical cells becomes a boundary between runs.
 */
internal fun glyphRuns(row: List<TermCell>, highlight: RowHighlight? = null): List<GlyphRun> {
    val runs = ArrayList<GlyphRun>()
    fun kindAt(col: Int) = highlight?.kindAt(col) ?: HighlightKind.None
    var g = 0
    while (g < row.size) {
        val cell = row[g]
        when {
            cell.width == CellWidth.Continuation -> g++
            cell.width == CellWidth.Wide -> {
                runs.add(GlyphRun(g, cell.text, 2, cell.style, kindAt(g))); g++
            }
            !cell.isPlainAscii() -> {
                runs.add(GlyphRun(g, cell.text, 1, cell.style, kindAt(g))); g++
            }
            else -> {
                val st = cell.style
                val kind = kindAt(g)
                val start = g
                val sb = StringBuilder()
                fun continuesRun(at: Int): Boolean {
                    if (at >= row.size) return false
                    val c = row[at]
                    return c.width == CellWidth.Single && c.style == st && c.isPlainAscii() && kindAt(at) == kind
                }
                while (continuesRun(g)) {
                    sb.append(row[g].text); g++
                }
                runs.add(GlyphRun(start, sb.toString(), g - start, st, kind))
            }
        }
    }
    return runs
}

/**
 * [TextStyle] for drawing a cell glyph: the base monospace style + color/weight/underline from
 * [TermStyle]. The background is removed (the overlay draws it in a separate layer across the full cell width).
 */
internal fun TermStyle.toGlyphStyle(base: TextStyle, palette: Palette, theme: TerminalTheme): TextStyle =
    base.merge(toSpanStyle(palette, theme).copy(background = Color.Unspecified))

internal fun TermStyle.toSpanStyle(palette: Palette, theme: TerminalTheme): SpanStyle {
    // inverse swaps text and background; with a default background it becomes the terminal background color.
    val resolvedFg = fg.toComposeColor(theme, palette)
    val resolvedBg = if (bg == TermColor.Default) theme.background else bg.toComposeColor(theme, palette)
    var fgColor = if (inverse) resolvedBg else resolvedFg
    val bgColor = when {
        inverse -> resolvedFg
        bg == TermColor.Default -> Color.Unspecified
        else -> resolvedBg
    }
    if (dim) fgColor = fgColor.copy(alpha = 0.6f)
    // Transparent, not painted-as-background: a bg-colored glyph is faintly legible under the
    // half-alpha selection and search washes drawn beneath the text - the concealed character
    // must contribute no strokes at all. LAST, so no later attribute can restore visibility:
    // dim's copy(alpha = 0.6f) REPLACES alpha, and applied after Transparent (= black at alpha 0)
    // it would paint the secret in 60% black. The cell's real background is painted separately
    // by cellBgColor - this override touches only the glyph strokes.
    if (hidden) fgColor = Color.Transparent
    // Underline (including modern 4:x forms and the SGR 58 color) is drawn manually in Canvas — Compose
    // TextDecoration can't do wavy/dotted/double or a separate color. Here only strikethrough, which is native.
    return SpanStyle(
        color = fgColor,
        background = bgColor,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
    )
}

/** Palette overrides (OSC 4/104): index 0..255 → Rgb. Empty — theme defaults are used. */
private typealias Palette = Map<Int, TermColor.Rgb>

/** Precomputed PathEffects for dotted/dashed underline (depend only on cell height). */
internal data class UnderlineEffects(val dotted: PathEffect, val dashed: PathEffect)

/** OSC 8 hyperlink underline style: a single line in the theme cyan (primary cyan). */
internal val LINK_UNDERLINE_STYLE = TermStyle(
    underlineStyle = UnderlineStyle.Single,
    underlineColor = TermColor.Rgb(0x2B, 0xBD, 0xEE),
)

/**
 * Underline line color: [TermStyle.underlineColor], or with [TermColor.Default] it follows the text
 * color (accounting for inverse and dim). Rendered separately from the glyph, so the color is computed here.
 */
internal fun TermStyle.underlineDrawColor(palette: Palette, theme: TerminalTheme): Color {
    // A concealed cell must not reveal even the POSITION of its text: an SGR 8;4 underline in the
    // text color would trace the run of the hidden characters.
    if (hidden) return Color.Transparent
    val base = if (underlineColor == TermColor.Default) {
        if (inverse) {
            if (bg == TermColor.Default) theme.background else bg.toComposeColor(theme, palette)
        } else fg.toComposeColor(theme, palette)
    } else {
        underlineColor.toComposeColor(theme, palette)
    }
    return if (dim) base.copy(alpha = 0.6f) else base
}

/**
 * Draws the underline of the required shape (modern SGR `4:x`) at the bottom edge of a cell/run.
 * [left]/[width] — the horizontal segment, [top] — the row top, [chh] — the cell height.
 */
internal fun DrawScope.drawCellUnderline(style: TermStyle, left: Float, top: Float, width: Float, chh: Float, palette: Palette, effects: UnderlineEffects, theme: TerminalTheme) {
    if (style.underlineStyle == UnderlineStyle.None) return
    val color = style.underlineDrawColor(palette, theme)
    val thickness = (chh / 14f).coerceAtLeast(1f)
    val y = top + chh - thickness * 1.5f
    val right = left + width
    when (style.underlineStyle) {
        UnderlineStyle.None -> {}
        UnderlineStyle.Single ->
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = thickness)
        UnderlineStyle.Double -> {
            val gap = thickness * 1.6f
            drawLine(color, Offset(left, y - gap), Offset(right, y - gap), strokeWidth = thickness)
            drawLine(color, Offset(left, y + gap), Offset(right, y + gap), strokeWidth = thickness)
        }
        UnderlineStyle.Dotted ->
            drawLine(
                color, Offset(left, y), Offset(right, y), strokeWidth = thickness,
                pathEffect = effects.dotted,
            )
        UnderlineStyle.Dashed ->
            drawLine(
                color, Offset(left, y), Offset(right, y), strokeWidth = thickness,
                pathEffect = effects.dashed,
            )
        UnderlineStyle.Curly -> {
            val amp = thickness * 1.6f
            val halfPeriod = (chh / 6f).coerceAtLeast(2f)
            val path = Path().apply {
                moveTo(left, y)
                var x = left
                var up = true
                while (x < right) {
                    val nx = (x + halfPeriod).coerceAtMost(right)
                    val peak = if (up) y - amp else y + amp
                    quadraticTo((x + nx) / 2f, peak, nx, y)
                    x = nx
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = thickness))
        }
    }
}

/**
 * Converts [TermColor] to a Compose Color: Default — the contextual color; Rgb — directly; Indexed —
 * the xterm palette, where the first 16 indices come from the active theme ([TerminalTheme.ansi]) and
 * 16..255 is the standard 6×6×6 cube and grayscale.
 */
private fun TermColor.toComposeColor(theme: TerminalTheme, palette: Palette): Color = when (this) {
    TermColor.Default -> theme.foreground
    is TermColor.Rgb -> Color(r, g, b)
    is TermColor.Indexed -> xtermColor(index, palette, theme)
}

/** ANSI 0..15 from the active theme + the standard xterm cube/grayscale for 16..255; an OSC 4 override takes priority. */
private fun xtermColor(index: Int, palette: Palette, theme: TerminalTheme): Color {
    palette[index]?.let { return Color(it.r, it.g, it.b) }
    if (index in 0..15) return theme.ansi[index]
    return xtermCubeColor(index, fallback = theme.foreground)
}

/** The standard xterm 6×6×6 cube (16..231) and grayscale ramp (232..255); theme-independent. */
private fun xtermCubeColor(index: Int, fallback: Color): Color = when (index) {
    in 16..231 -> {
        val n = index - 16
        val r = n / 36; val g = (n / 6) % 6; val b = n % 6
        fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(lvl(r), lvl(g), lvl(b))
    }
    in 232..255 -> { val v = 8 + (index - 232) * 10; Color(v, v, v) }
    else -> fallback
}
