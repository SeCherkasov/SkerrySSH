package app.skerry.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.TerminalPos
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

/** Радиус «капли» тач-маркера выделения и радиус зоны попадания пальца по нему. */
private const val HANDLE_RADIUS_DP = 7
private const val HANDLE_TOUCH_RADIUS_DP = 22

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
 * Выделение: на мыши — перетаскивание сразу тянет линейный диапазон ([TerminalSelection]) поверх
 * сетки (одиночный клик снимает выделение, фокус возвращается); на таче обычный drag отдаётся
 * прокрутке, а выделение начинается после long-press. Диапазон подсвечивается полупрозрачным cyan.
 * Копирование: `Ctrl+Shift+C` (desktop) и системное текстовое меню «Copy» над выделением, которое
 * всплывает по окончании тач-выделения ([LocalTextToolbar]). Печать снимает выделение и меню.
 *
 * [imeInput] включает мобильный путь ввода: софт-клавиатура не шлёт key-события в
 * [onPreviewKeyEvent], поэтому ввод снимается со скрытого `BasicTextField` ([imeDeltaToPty]).
 * На desktop оставлен `false` — там работает физическая клавиатура через [mapTerminalKey].
 *
 * [imeTransform] (только для IME-пути) пост-обрабатывает непустой результат [imeDeltaToPty] перед
 * отправкой — мобильная клавишная панель пропускает через него sticky-ctrl ([app.skerry.ui.design.applyStickyCtrl]),
 * чтобы Ctrl+<буква> работал и с софт-клавиатуры, а не только с клавиш панели.
 */
@Composable
fun TerminalScreen(
    state: TerminalScreenState,
    modifier: Modifier = Modifier,
    imeInput: Boolean = false,
    imeTransform: ((String) -> String)? = null,
) {
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
    // Скрытое IME-поле (тач-ввод): держит фокус/клавиатуру, всегда сброшено к якорю.
    val imeFocusRequester = remember { FocusRequester() }
    val imeBaseline = remember { TextFieldValue(ANCHOR, selection = TextRange(ANCHOR.length)) }
    var imeValue by remember { mutableStateOf(imeBaseline) }
    val clipboard = LocalClipboardManager.current
    val textToolbar = LocalTextToolbar.current
    // Контроллер софт-клавиатуры: на таче поднимаем её явно, т.к. requestFocus() на уже
    // сфокусированном скрытом поле — no-op (после скрытия фокус остаётся, клавиатура не всплывает).
    val keyboard = LocalSoftwareKeyboardController.current
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Размер моноширинной ячейки в пикселях — фолбэк-перевод координат мыши в ячейку до того, как
    // придёт реальный лэйаут текста ([layout]). Точные позиции (подсветка/маркеры/хит-тест) берём
    // из самого [TextLayoutResult], чтобы совпадать с рендером при любом шрифте и системном масштабе.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val metrics = remember(textStyle, density) {
        val sample = measurer.measure(AnnotatedString("MMMMMMMMMM"), textStyle)
        TerminalMetrics(
            cellWidth = sample.size.width / 10f,
            cellHeight = with(density) { LINE_HEIGHT_SP.sp.toPx() },
        )
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val handleTouchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }

    // Активная сессия забирает фокус, чтобы можно было сразу печатать. На таче фокус держит
    // скрытое IME-поле (оно же поднимает софт-клавиатуру), на desktop — сам терминал.
    LaunchedEffect(state) { if (!closed) (if (imeInput) imeFocusRequester else focusRequester).requestFocus() }

    // Автоскролл вниз по мере нового вывода (новый снимок экрана на каждый чанк).
    LaunchedEffect(state.screen) { scroll.scrollTo(scroll.maxValue) }

    // Размер вьюпорта в ячейках → PTY/эмулятор: при первом лэйауте и ресайзе окна. Без этого
    // сетка остаётся дефолтной 80×24 и широкий вывод рвётся. state.resize гасит дубли сам.
    // Меряем ВНЕШНИЙ Box (вьюпорт), а не прокручиваемый Text: у того размер = высота всего контента.
    val paddingPx = with(density) { PADDING_DP.dp.toPx() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewportSize, metrics, paddingPx) {
        if (viewportSize.width == 0 || viewportSize.height == 0) return@LaunchedEffect
        state.resize(gridSizeFor(viewportSize.width.toFloat(), viewportSize.height.toFloat(), paddingPx, metrics))
    }

    val rendered = remember(state.screen, state.cursorRow, state.cursorCol, state.selection, closed) {
        renderScreen(state.screen, state.cursorRow, state.cursorCol, state.selection, showCursor = !closed)
    }

    fun cellAt(x: Float, y: Float) = cellAtOffset(x, y, metrics)

    // Координата указателя (содержимое, со скроллом) → ячейка через фактический лэйаут: совпадает с
    // подсветкой и маркерами. До прихода лэйаута — фолбэк на арифметику [cellAt].
    fun posAt(x: Float, y: Float): TerminalPos {
        val l = layout ?: return cellAt(x, y)
        val screen = state.screen
        if (screen.isEmpty()) return cellAt(x, y)
        var rem = l.getOffsetForPosition(Offset(x, y))
        var row = 0
        while (row < screen.lastIndex) {
            var len = screen[row].size
            if (!closed && row == state.cursorRow && state.cursorCol >= screen[row].size) len += 1
            if (rem <= len) break
            rem -= len + 1
            row++
        }
        return TerminalPos(row, rem.coerceIn(0, screen[row].size))
    }

    // Линейный индекс ячейки (row, col) в строке [rendered]: суммируем длины предыдущих строк
    // (+1 на '\n'), плюс «хвостовой» пробел курсора, который renderScreen дорисовывает в конце
    // строки курсора. Нужен, чтобы спросить у TextLayoutResult точную пиксельную позицию.
    fun lineOffset(pos: TerminalPos): Int {
        val screen = state.screen
        if (screen.isEmpty()) return 0
        val row = pos.row.coerceIn(0, screen.lastIndex)
        var off = 0
        for (r in 0 until row) {
            off += screen[r].size
            if (!closed && r == state.cursorRow && state.cursorCol >= screen[r].size) off += 1
            off += 1 // '\n'
        }
        return off + pos.col.coerceIn(0, screen[row].size)
    }

    // Пиксельная позиция границы выделения (содержимое, без скролла) из фактического лэйаута —
    // совпадает с подсветкой, которую рисует Text. null, пока лэйаут не пришёл.
    fun handleAnchor(pos: TerminalPos): Offset? {
        val l = layout ?: return null
        if (state.screen.isEmpty()) return null
        val off = lineOffset(pos)
        val line = l.getLineForOffset(off)
        return Offset(
            x = l.getHorizontalPosition(off, usePrimaryDirection = true),
            y = l.getLineBottom(line),
        )
    }

    fun copySelection() {
        state.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }
    }

    // Системное текстовое меню «Copy» над выделением — тач-аффорданс копирования (на мыши Ctrl+Shift+C).
    fun showCopyMenu() {
        val sel = state.selection ?: return
        if (state.selectedText() == null) return
        val coords = layoutCoords ?: return
        if (!coords.isAttached) return
        val local = selectionAnchorRect(sel, metrics)
        val topLeft = coords.localToWindow(Offset(local.left, local.top))
        val bottomRight = coords.localToWindow(Offset(local.right, local.bottom))
        textToolbar.showMenu(
            rect = Rect(topLeft, bottomRight),
            onCopyRequested = {
                copySelection()
                state.clearSelection()
                textToolbar.hide()
            },
        )
    }

    Box(modifier.onSizeChanged { viewportSize = it }) {
      Text(
        text = rendered,
        style = textStyle,
        onTextLayout = { layout = it },
        modifier = Modifier
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
                    copySelection()
                    return@onPreviewKeyEvent true
                }
                val bytes = mapTerminalKey(
                    key = event.key,
                    ctrl = event.isCtrlPressed,
                    codePoint = event.utf16CodePoint,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    applicationCursor = state.applicationCursorKeys,
                )
                if (bytes != null) {
                    state.clearSelection()
                    textToolbar.hide()
                    state.send(bytes)
                    true
                } else {
                    false
                }
            }
            .focusable()
            .onGloballyPositioned { layoutCoords = it }
            .pointerInput(metrics) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    textToolbar.hide()
                    if (down.type == PointerType.Mouse) {
                        // Мышь: фокус сразу (desktop-ввод с клавиатуры), выделение по перетаскиванию,
                        // одиночный клик — снять выделение.
                        focusRequester.requestFocus()
                        state.beginSelection(posAt(down.position.x, down.position.y))
                        val dragged = drag(down.id) { change ->
                            change.consume()
                            state.extendSelection(posAt(change.position.x, change.position.y))
                        }
                        if (!dragged || state.selection?.isEmpty != false) state.clearSelection()
                    } else {
                        // Тач: сначала — попал ли палец в маркер уже существующего выделения.
                        // Если да, перетаскиваем эту границу (вторую держим) — корректировка краёв,
                        // как в мессенджерах; по окончании обновляем меню «Copy».
                        val sel = state.selection
                        val handle = if (sel != null && !sel.isEmpty) {
                            val s = handleAnchor(sel.start)
                            val e = handleAnchor(sel.end)
                            val ds = s?.let { (down.position - it).getDistance() } ?: Float.MAX_VALUE
                            val de = e?.let { (down.position - it).getDistance() } ?: Float.MAX_VALUE
                            when {
                                ds <= handleTouchRadiusPx && ds <= de -> SelectionHandle.START
                                de <= handleTouchRadiusPx -> SelectionHandle.END
                                else -> null
                            }
                        } else null
                        if (handle != null) {
                            drag(down.id) { change ->
                                change.consume()
                                val pos = posAt(change.position.x, change.position.y)
                                if (handle == SelectionHandle.START) state.moveSelectionStart(pos)
                                else state.moveSelectionEnd(pos)
                            }
                            if (state.selection?.isEmpty != false) state.clearSelection() else showCopyMenu()
                            return@awaitEachGesture
                        }
                        // Иначе разводим жесты, чтобы тап-для-клавиатуры и long-press-для-выделения
                        // не дрались. Long-press → режим выделения (клавиатуру НЕ поднимаем);
                        // короткий тап → клавиатура; движение пальцем уходит в прокрутку.
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held != null) {
                            // По зажатию сразу выделяем СЛОВО под пальцем (как в мессенджерах) —
                            // выделение и маркеры видны мгновенно, без необходимости двигать палец.
                            // Дальнейший drag тянет границу от слова; меню «Copy» поднимаем в конце.
                            state.selectWordAt(posAt(held.position.x, held.position.y))
                            drag(held.id) { change ->
                                change.consume()
                                state.extendSelection(posAt(change.position.x, change.position.y))
                            }
                            if (state.selection?.isEmpty != false) state.clearSelection() else showCopyMenu()
                        } else if (imeInput) {
                            // Не long-press: если палец уже отпущен — это тап, поднимаем клавиатуру;
                            // если ещё на экране (жест забрала прокрутка) — не трогаем.
                            val released = currentEvent.changes.none { it.id == down.id && it.pressed }
                            if (released) {
                                imeFocusRequester.requestFocus()
                                keyboard?.show()
                            }
                        }
                    }
                }
            },
      )

      // Тач-маркеры выделения («капли» по краям). Рисуем только на мобильном пути ([imeInput]):
      // оверлей внутри того же padding, что и текст, со сдвигом по вертикали на текущую прокрутку,
      // чтобы маркеры держались на границах выделения при скролле. На мыши (desktop) их нет.
      if (imeInput && !closed) {
          val sel = state.selection
          val startAnchor = sel?.takeIf { !it.isEmpty }?.let { handleAnchor(it.start) }
          val endAnchor = sel?.takeIf { !it.isEmpty }?.let { handleAnchor(it.end) }
          if (startAnchor != null && endAnchor != null) {
              Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
                  val dy = -scroll.value.toFloat()
                  drawSelectionHandle(startAnchor.copy(y = startAnchor.y + dy), handleRadiusPx, metrics.cellHeight, SelectionHandle.START)
                  drawSelectionHandle(endAnchor.copy(y = endAnchor.y + dy), handleRadiusPx, metrics.cellHeight, SelectionHandle.END)
              }
          }
      }

      // Тач-ввод: невидимое поле снимает символы софт-клавиатуры. Диффим против якоря
      // ([imeDeltaToPty]) и сразу сбрасываем — поле служит лишь «воронкой» в PTY, не хранит текст.
      if (imeInput && !closed) {
          BasicTextField(
              value = imeValue,
              onValueChange = { nv ->
                  val raw = imeDeltaToPty(ANCHOR, nv.text)
                  // sticky-ctrl и т.п. применяются только к реальному вводу (не к пустой дельте).
                  val out = if (raw.isEmpty()) raw else imeTransform?.invoke(raw) ?: raw
                  if (out.isNotEmpty()) {
                      state.clearSelection()
                      textToolbar.hide()
                      state.send(out)
                  }
                  imeValue = imeBaseline
              },
              modifier = Modifier.size(1.dp).focusRequester(imeFocusRequester),
              textStyle = TextStyle(color = Color.Transparent),
              cursorBrush = SolidColor(Color.Transparent),
              keyboardOptions = KeyboardOptions(
                  capitalization = KeyboardCapitalization.None,
                  autoCorrectEnabled = false,
                  keyboardType = KeyboardType.Ascii,
                  imeAction = ImeAction.None,
              ),
          )
      }
    }
}

/** Цвет курсора-блока и цвет символа под ним (контраст на cyan). */
private val cursorBg = SkerryColors.cyan
private val cursorFg = SkerryColors.terminalBg

/** Полупрозрачная подсветка выделения — поверх собственного фона ячейки. */
private val selectionBg = SkerryColors.cyan.copy(alpha = 0.3f)

/** Цвет тач-маркеров выделения. */
private val handleColor = SkerryColors.cyan

/**
 * Рисует один тач-маркер выделения: вертикальную «ножку» вдоль границы ячейки (высотой в строку)
 * и «каплю»-кружок под якорем, смещённый наружу от текста (start — влево, end — вправо), как в
 * системных хэндлах выделения. [anchor] — угловая точка границы в координатах канвы.
 */
private fun DrawScope.drawSelectionHandle(
    anchor: Offset,
    radius: Float,
    cellHeight: Float,
    which: SelectionHandle,
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

private fun TermStyle.toSpanStyle(): SpanStyle {
    // inverse меняет местами текст и фон; при дефолтном фоне он становится цветом фона терминала.
    val resolvedFg = fg.toComposeColor(SkerryColors.text)
    val resolvedBg = if (bg == TermColor.Default) SkerryColors.terminalBg else bg.toComposeColor(SkerryColors.text)
    var fgColor = if (inverse) resolvedBg else resolvedFg
    val bgColor = when {
        inverse -> resolvedFg
        bg == TermColor.Default -> Color.Unspecified
        else -> resolvedBg
    }
    if (hidden) fgColor = bgColor.takeIf { it != Color.Unspecified } ?: SkerryColors.terminalBg
    if (dim) fgColor = fgColor.copy(alpha = 0.6f)
    val decoration = when {
        underline && strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> null
    }
    return SpanStyle(
        color = fgColor,
        background = bgColor,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = decoration,
    )
}

/**
 * Перевод [TermColor] в Compose Color: Default — цвет из контекста; Rgb — напрямую; Indexed —
 * xterm-палитра, где первые 16 индексов взяты из темы «night sea», а 16..255 — стандартный
 * 6×6×6 куб и градации серого.
 */
private fun TermColor.toComposeColor(default: Color): Color = when (this) {
    TermColor.Default -> default
    is TermColor.Rgb -> Color(r, g, b)
    is TermColor.Indexed -> xtermColor(index)
}

/** ANSI 0..15 под «night sea» + стандартный xterm-куб/grayscale для 16..255. */
private fun xtermColor(index: Int): Color = when (index) {
    0 -> Color(0xFF2A3540); 1 -> Color(0xFFE94B4B); 2 -> Color(0xFF5DCE9E); 3 -> Color(0xFFF2A65A)
    4 -> Color(0xFF4A9EDB); 5 -> Color(0xFFC792EA); 6 -> Color(0xFF2BBDEE); 7 -> Color(0xFFC9D6DE)
    8 -> Color(0xFF5A7080); 9 -> Color(0xFFFF6B6B); 10 -> Color(0xFF7FE9B8); 11 -> Color(0xFFFFC078)
    12 -> Color(0xFF6FC3F5); 13 -> Color(0xFFE0A8FF); 14 -> Color(0xFF5FD1F4); 15 -> Color(0xFFFFFFFF)
    in 16..231 -> {
        val n = index - 16
        val r = n / 36; val g = (n / 6) % 6; val b = n % 6
        fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(lvl(r), lvl(g), lvl(b))
    }
    in 232..255 -> { val v = 8 + (index - 232) * 10; Color(v, v, v) }
    else -> Color(0xFFC9D6DE)
}
