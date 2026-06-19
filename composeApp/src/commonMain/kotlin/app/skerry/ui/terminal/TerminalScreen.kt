package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import app.skerry.ui.theme.SkerryColors
import org.jetbrains.compose.resources.Font

private const val FONT_SIZE_SP = 13
private const val LINE_HEIGHT_SP = 18
private const val PADDING_DP = 14

/**
 * Моноширинное семейство Skerry — JetBrains Mono из compose-resources.
 * `Font(...)` сам @Composable и кэширует ресурс внутри, поэтому remember не нужен.
 */
@Composable
fun rememberJetBrainsMono(): FontFamily = FontFamily(
    Font(Res.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    Font(Res.font.jetbrainsmono_bold, weight = FontWeight.Bold),
)

/**
 * Интерактивный терминал: рендерит модель экрана [TerminalScreenState.screen] (сетку ячеек с
 * цветом/жирностью из [app.skerry.shared.terminal.TerminalEmulator]) и блок-курсор в позиции
 * курсора. Это активная зона ввода — фокус держится здесь, нажатия идут в PTY посимвольно
 * ([mapTerminalKey]); эхо рисует сам shell. Командной строки под терминалом НЕТ — нижняя строка
 * окна отведена под AI-ассистента (Phase 2).
 *
 * Выделение мышью: перетаскивание тянет линейный диапазон ([TerminalSelection]) поверх сетки,
 * подсвечивается полупрозрачным cyan; `Ctrl+Shift+C` копирует его в буфер обмена. Клик снимает
 * выделение и возвращает фокус; печать тоже снимает (как в обычных терминалах).
 */
@Composable
fun TerminalScreen(state: TerminalScreenState, modifier: Modifier = Modifier) {
    val mono = rememberJetBrainsMono()
    val textStyle = remember(mono) {
        TextStyle(
            fontFamily = mono,
            fontSize = FONT_SIZE_SP.sp,
            lineHeight = LINE_HEIGHT_SP.sp,
            color = SkerryColors.text,
        )
    }
    val sessionState by state.state.collectAsState()
    val closed = sessionState == TerminalState.Closed
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current

    // Размер моноширинной ячейки в пикселях — для перевода координат мыши в ячейку.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val metrics = remember(textStyle, density) {
        val sample = measurer.measure(AnnotatedString("MMMMMMMMMM"), textStyle)
        TerminalMetrics(
            cellWidth = sample.size.width / 10f,
            cellHeight = with(density) { LINE_HEIGHT_SP.sp.toPx() },
        )
    }

    // Активная сессия забирает фокус, чтобы можно было сразу печатать.
    LaunchedEffect(state) { if (!closed) focusRequester.requestFocus() }

    // Автоскролл вниз по мере нового вывода (новый снимок экрана на каждый чанк).
    LaunchedEffect(state.screen) { scroll.scrollTo(scroll.maxValue) }

    val rendered = remember(state.screen, state.cursorRow, state.cursorCol, state.selection, closed) {
        renderScreen(state.screen, state.cursorRow, state.cursorCol, state.selection, showCursor = !closed)
    }

    fun cellAt(x: Float, y: Float) = cellAtOffset(x, y, metrics)

    Text(
        text = rendered,
        style = textStyle,
        modifier = modifier
            .fillMaxSize()
            .background(SkerryColors.terminalBg)
            .verticalScroll(scroll)
            .padding(PADDING_DP.dp)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || closed) return@onPreviewKeyEvent false
                // Ctrl+Shift+C — копирование выделения (Ctrl+C остаётся SIGINT для shell).
                if (event.isCtrlPressed && event.isShiftPressed && event.key == Key.C) {
                    state.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }
                    return@onPreviewKeyEvent true
                }
                val bytes = mapTerminalKey(event.key, event.isCtrlPressed, event.utf16CodePoint)
                if (bytes != null) {
                    state.clearSelection()
                    state.send(bytes)
                    true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(metrics) {
                detectDragGestures(
                    onDragStart = { offset ->
                        focusRequester.requestFocus()
                        state.beginSelection(cellAt(offset.x, offset.y))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        state.extendSelection(cellAt(change.position.x, change.position.y))
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    focusRequester.requestFocus()
                    state.clearSelection()
                }
            },
    )
}

/** Цвет курсора-блока и цвет символа под ним (контраст на cyan). */
private val cursorBg = SkerryColors.cyan
private val cursorFg = SkerryColors.terminalBg

/** Полупрозрачная подсветка выделения — поверх собственного фона ячейки. */
private val selectionBg = SkerryColors.cyan.copy(alpha = 0.3f)

/**
 * Собирает [AnnotatedString] из сетки ячеек: для каждой ячейки считается эффективный стиль
 * (базовый + подсветка выделения + блок-курсор), соседние ячейки одинакового стиля схлопываются
 * в один span. Строки разделяются `\n`.
 */
private fun renderScreen(
    screen: List<List<TermCell>>,
    cursorRow: Int,
    cursorCol: Int,
    selection: TerminalSelection?,
    showCursor: Boolean,
): AnnotatedString = buildAnnotatedString {
    for (r in screen.indices) {
        val row = screen[r]
        fun spanAt(c: Int) = cellSpan(
            row[c],
            isCursor = showCursor && r == cursorRow && c == cursorCol,
            isSelected = selection?.contains(r, c) == true,
        )
        var c = 0
        while (c < row.size) {
            val span = spanAt(c)
            val start = c
            c++
            // Схлопываем подряд идущие ячейки с тем же эффективным стилем.
            while (c < row.size && spanAt(c) == span) c++
            withStyle(span) { for (k in start until c) append(row[k].char) }
        }
        // Курсор за концом строки — рисуем блок-пробел.
        if (showCursor && r == cursorRow && cursorCol >= row.size) {
            withStyle(SpanStyle(color = cursorFg, background = cursorBg)) { append(" ") }
        }
        if (r < screen.lastIndex) append("\n")
    }
}

/** Эффективный стиль ячейки: базовый стиль → подсветка выделения → блок-курсор (курсор главнее). */
private fun cellSpan(cell: TermCell, isCursor: Boolean, isSelected: Boolean): SpanStyle {
    val base = cell.style.toSpanStyle()
    return when {
        isCursor -> base.copy(color = cursorFg, background = cursorBg)
        isSelected -> base.copy(background = selectionBg)
        else -> base
    }
}

private fun TermStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = fg.toComposeColor(SkerryColors.text),
    background = if (bg == TermColor.Default) Color.Unspecified else bg.toComposeColor(SkerryColors.text),
    fontWeight = if (bold) FontWeight.Bold else null,
)

/** ANSI-палитра под тему «night sea»; Default берёт цвет из контекста (текст/прозрачный фон). */
private fun TermColor.toComposeColor(default: Color): Color = when (this) {
    TermColor.Default -> default
    TermColor.Black -> Color(0xFF2A3540)
    TermColor.Red -> Color(0xFFE94B4B)
    TermColor.Green -> Color(0xFF5DCE9E)
    TermColor.Yellow -> Color(0xFFF2A65A)
    TermColor.Blue -> Color(0xFF4A9EDB)
    TermColor.Magenta -> Color(0xFFC792EA)
    TermColor.Cyan -> Color(0xFF2BBDEE)
    TermColor.White -> Color(0xFFC9D6DE)
    TermColor.BrightBlack -> Color(0xFF5A7080)
    TermColor.BrightRed -> Color(0xFFFF6B6B)
    TermColor.BrightGreen -> Color(0xFF7FE9B8)
    TermColor.BrightYellow -> Color(0xFFFFC078)
    TermColor.BrightBlue -> Color(0xFF6FC3F5)
    TermColor.BrightMagenta -> Color(0xFFE0A8FF)
    TermColor.BrightCyan -> Color(0xFF5FD1F4)
    TermColor.BrightWhite -> Color(0xFFFFFFFF)
}
