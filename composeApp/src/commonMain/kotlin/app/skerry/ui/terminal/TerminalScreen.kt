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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.UnderlineStyle
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.ArrowKey
import app.skerry.ui.design.arrowSequence
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import app.skerry.ui.theme.SkerryColors
import org.jetbrains.compose.resources.Font

/** Порог между кликами (мс), в пределах которого они складываются в двойной/тройной. */
private const val DOUBLE_CLICK_MS = 350

/** Полупериод мигания курсора (мс) — стандартный xterm-ритм ~530 мс на фазу. */
private const val CURSOR_BLINK_MS = 530L

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

    // Подсчёт кликов мышью для двойного (слово) / тройного (строка) выделения: запоминаем время и
    // позицию предыдущего клика; повтор в той же ячейке за порог времени наращивает счётчик.
    var clickCount by remember { mutableStateOf(0) }
    var lastClickMark by remember { mutableStateOf<TimeMark?>(null) }
    var lastClickPos by remember { mutableStateOf<TerminalPos?>(null) }

    // Размер моноширинной ячейки в пикселях — единственный источник правды по геометрии: глифы,
    // фон, выделение, курсор, мышь и маркеры считаются от него арифметикой (col*cellWidth /
    // row*cellHeight), поэтому всё совпадает при любом шрифте и системном масштабе.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val metrics = remember(textStyle, density) {
        val sample = measurer.measure(AnnotatedString("MMMMMMMMMM"), textStyle)
        TerminalMetrics(
            cellWidth = sample.size.width / 10f,
            cellHeight = with(density) { LINE_HEIGHT_SP.sp.toPx() },
        )
    }
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

    // Фаза мигания курсора: при включённом blink дёргаем булеву раз в полупериод; иначе курсор
    // стоит постоянно. Курсор рисуется ОВЕРЛЕЕМ (см. ниже), поэтому мигание перерисовывает лишь
    // маленький Canvas, а не весь экранный текст.
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(state.cursorBlink, state.cursorVisible, closed) {
        if (!state.cursorBlink || !state.cursorVisible || closed) {
            blinkOn = true
            return@LaunchedEffect
        }
        while (true) {
            blinkOn = true
            delay(CURSOR_BLINK_MS)
            blinkOn = false
            delay(CURSOR_BLINK_MS)
        }
    }
    val cursorVisibleNow = !closed && state.cursorVisible && blinkOn

    // Структурная подложка под Text: лишь задаёт высоту контента для прокрутки (по числу строк) и
    // несёт модификаторы ввода/IME — глифы рисует отдельный пер-клеточный оверлей (см. ниже), а сам
    // текст невидим. Высота = число строк (scrollback + экран); ширину даёт fillMaxWidth.
    val structural = remember(state.screen.size) {
        AnnotatedString("\n".repeat((state.screen.size - 1).coerceAtLeast(0)))
    }

    fun cellAt(x: Float, y: Float) = cellAtOffset(x, y, metrics)

    // Координата указателя → ячейка чистой арифметикой: pointerInput стоит после verticalScroll и
    // padding, поэтому offset уже в координатах контента (со скроллом, без отступа). Колонку/строку
    // поджимаем к фактической сетке, чтобы выделение не уходило за её пределы.
    fun posAt(x: Float, y: Float): TerminalPos {
        val p = cellAt(x, y)
        val screen = state.screen
        if (screen.isEmpty()) return p
        val row = p.row.coerceIn(0, screen.lastIndex)
        return TerminalPos(row, p.col.coerceIn(0, screen[row].size))
    }

    // Якорь границы выделения в координатах контента (нижний угол ячейки) — арифметика, совпадающая
    // с сеткой глифов. Канвас маркеров рисует с поправкой на прокрутку.
    fun handleAnchor(pos: TerminalPos): Offset =
        Offset(pos.col * metrics.cellWidth, (pos.row + 1) * metrics.cellHeight)

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

    Box(modifier.onSizeChanged { viewportSize = it }.background(SkerryColors.terminalBg)) {
      // Весь видимый экран рисуем ОДНИМ пер-клеточным оверлеем по моноширинной сетке (а не потоковым
      // Text): фон ячеек на полную ширину строки (включая хвостовые reverse-пробелы TUI), подсветку
      // выделения и сами глифы — каждый по своей колонке `col*cellWidth`. Так широкие символы (CJK,
      // emoji) и их continuation-клетки держат сетку, а курсор/мышь/маркеры (та же арифметика) с ней
      // совпадают. Геометрия как у курсора: тот же padding и сдвиг на прокрутку. Сам Text ниже —
      // невидимая подложка (высота для скролла + приём ввода).
      if (state.screen.isNotEmpty()) {
          val sel = state.selection
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
              val scrollPx = scroll.value.toFloat()
              val screen = state.screen
              val cw = metrics.cellWidth
              val chh = metrics.cellHeight
              for (r in screen.indices) {
                  val top = r * chh - scrollPx
                  if (top + chh < 0f || top > size.height) continue
                  val row = screen[r]
                  // 1) Фон ячеек — раны одного цвета схлопываем; хвостовой ран тянем до края вьюпорта.
                  var c = 0
                  while (c < row.size) {
                      val color = cellBgColor(row[c].style)
                      if (color == null) { c++; continue }
                      val s = c; c++
                      while (c < row.size && cellBgColor(row[c].style) == color) c++
                      val left = s * cw
                      val right = if (c >= row.size) size.width else c * cw
                      drawRect(color, topLeft = Offset(left, top), size = Size(right - left, chh))
                  }
                  // 2) Подсветка выделения — поверх фона, под глифами.
                  if (sel != null && !sel.isEmpty) {
                      var k = 0
                      while (k < row.size) {
                          if (!sel.contains(r, k)) { k++; continue }
                          val s = k
                          while (k < row.size && sel.contains(r, k)) k++
                          drawRect(selectionBg, topLeft = Offset(s * cw, top), size = Size((k - s) * cw, chh))
                      }
                  }
                  // 3) Глифы — раны подряд идущих одностилевых Single-клеток рисуем одним drawText
                  // (быстрый общий случай моноширинного текста); Wide-клетку — отдельно (её глиф шире
                  // одной колонки), Continuation — пропускаем (под широким символом глифа нет).
                  var g = 0
                  while (g < row.size) {
                      val cell = row[g]
                      if (cell.width == CellWidth.Continuation) { g++; continue }
                      if (cell.width == CellWidth.Wide) {
                          if (cell.text.isNotBlank()) {
                              drawText(measurer, cell.text, topLeft = Offset(g * cw, top), style = cell.style.toGlyphStyle(textStyle))
                          }
                          if (cell.style.underline) drawCellUnderline(cell.style, g * cw, top, 2 * cw, chh)
                          g++
                          continue
                      }
                      val st = cell.style
                      val sCol = g
                      val runText = buildString {
                          while (g < row.size && row[g].width == CellWidth.Single && row[g].style == st) {
                              append(row[g].text); g++
                          }
                      }
                      if (runText.isNotBlank()) {
                          drawText(measurer, runText, topLeft = Offset(sCol * cw, top), style = st.toGlyphStyle(textStyle))
                      }
                      // Подчёркивание тянем по всей ширине рана, в т.ч. под пробелами (как в xterm).
                      if (st.underline) drawCellUnderline(st, sCol * cw, top, (g - sCol) * cw, chh)
                  }
              }
          }
      }
      Text(
        text = structural,
        style = textStyle.copy(color = Color.Transparent),
        modifier = Modifier
            .fillMaxSize()
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
                // Ctrl+Shift+V и Shift+Insert — вставка из буфера обмена (bracketed-paste, если приложение включило).
                if ((event.isCtrlPressed && event.isShiftPressed && event.key == Key.V) ||
                    (event.isShiftPressed && event.key == Key.Insert)
                ) {
                    clipboard.getText()?.text?.let { state.paste(it) }
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
            // Колесо мыши: когда приложение слушает мышь — шлём wheel-отчёт; в alt-screen без
            // mouse-tracking (less/man) — стрелки (3 строки за «щелчок»), т.к. своего scrollback нет.
            // Перехватываем в Initial-проходе и гасим событие, чтобы verticalScroll не дёргал scrollback;
            // иначе (основной буфер, без репортинга) пропускаем — колесо листает scrollback штатно.
            .pointerInput(state, closed) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll || closed) continue
                        val reporting = state.mouseTracking != MouseTracking.Off
                        if (!reporting && !state.altScreen) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        if (dy != 0f) {
                            val up = dy < 0f
                            if (reporting) {
                                val pos = posAt(change.position.x, change.position.y)
                                state.reportMouse(if (up) MouseButton.WheelUp else MouseButton.WheelDown, MouseEventType.Press, pos)
                            } else {
                                val seq = arrowSequence(if (up) ArrowKey.Up else ArrowKey.Down, state.applicationCursorKeys)
                                repeat(3) { state.send(seq) }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            // Hover-репортинг для AnyEvent (DEC 1003): приложение хочет события движения и без
            // зажатой кнопки. Шлём Move только при смене ячейки (кнопка 3 = «не зажата» проставляется
            // внутри encodeMouseReport). Drag с зажатой кнопкой обрабатывает жестовый цикл ниже.
            .pointerInput(state, closed) {
                var lastHover: TerminalPos? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (closed || state.mouseTracking != MouseTracking.AnyEvent) continue
                        if (event.type != PointerEventType.Move) continue
                        val change = event.changes.firstOrNull { !it.pressed } ?: continue
                        val pos = posAt(change.position.x, change.position.y)
                        if (pos != lastHover) {
                            state.reportMouse(MouseButton.Left, MouseEventType.Move, pos)
                            lastHover = pos
                        }
                    }
                }
            }
            // Средняя/правая кнопки мыши: awaitFirstDown (в жесте ниже) реагирует только на основную
            // (левую) кнопку, поэтому средний/правый клик ловим здесь на сырых событиях. При активном
            // mouse-tracking — репортим press/release приложению; иначе средний/правый клик = вставка
            // (средний берёт PRIMARY-выделение X11 c откатом на буфер, правый — буфер обмена).
            .pointerInput(state, closed) {
                var reported: MouseButton? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                        if (closed) continue
                        val mods = event.keyboardModifiers
                        val shift = mods.isShiftPressed
                        val reporting = state.mouseTracking != MouseTracking.Off && !shift
                        val pos = posAt(change.position.x, change.position.y)
                        when (event.type) {
                            PointerEventType.Press -> {
                                val btn = when {
                                    event.buttons.isTertiaryPressed -> MouseButton.Middle
                                    event.buttons.isSecondaryPressed -> MouseButton.Right
                                    else -> null
                                } ?: continue
                                if (reporting) {
                                    state.reportMouse(btn, MouseEventType.Press, pos, shift, mods.isAltPressed, mods.isCtrlPressed)
                                    reported = btn
                                } else if (btn == MouseButton.Middle) {
                                    val text = readPrimarySelectionText()?.takeUnless { it.isBlank() }
                                        ?: clipboard.getText()?.text
                                    text?.let { state.paste(it) }
                                } else {
                                    clipboard.getText()?.text?.let { state.paste(it) }
                                }
                                change.consume()
                            }
                            PointerEventType.Release -> reported?.let { btn ->
                                state.reportMouse(btn, MouseEventType.Release, pos, shift, mods.isAltPressed, mods.isCtrlPressed)
                                reported = null
                                change.consume()
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput(metrics) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    textToolbar.hide()
                    if (down.type == PointerType.Mouse) {
                        focusRequester.requestFocus()
                        val mods = currentEvent.keyboardModifiers
                        val shift = mods.isShiftPressed
                        // Левая кнопка с зажатым мышиным трекингом: транслируем press/drag/release в
                        // отчёты, пока не отпущена. (Среднюю/правую ловит отдельный обработчик ниже —
                        // awaitFirstDown реагирует только на основную кнопку.) Shift форсит выделение.
                        if (state.mouseTracking != MouseTracking.Off && !shift) {
                            val ctrl = mods.isCtrlPressed
                            val alt = mods.isAltPressed
                            state.reportMouse(MouseButton.Left, MouseEventType.Press, posAt(down.position.x, down.position.y), shift, alt, ctrl)
                            down.consume()
                            var last = posAt(down.position.x, down.position.y)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: continue
                                if (change.pressed) {
                                    val pos = posAt(change.position.x, change.position.y)
                                    if (pos != last) {
                                        // В Normal-режиме (1000) drag не репортится — encodeMouseReport
                                        // вернёт false и ничего не отправит; для 1002/1003 уйдёт отчёт.
                                        state.reportMouse(MouseButton.Left, MouseEventType.Drag, pos, shift, alt, ctrl)
                                        last = pos
                                    }
                                    change.consume()
                                } else {
                                    state.reportMouse(MouseButton.Left, MouseEventType.Release, posAt(change.position.x, change.position.y), shift, alt, ctrl)
                                    change.consume()
                                    break
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Локальное выделение. Счётчик кликов: 1 — drag-выделение, 2 — слово, 3 — строка.
                        val pos = posAt(down.position.x, down.position.y)
                        val multi = lastClickPos == pos &&
                            lastClickMark?.let { it.elapsedNow() < DOUBLE_CLICK_MS.milliseconds } == true
                        clickCount = if (multi) clickCount + 1 else 1
                        lastClickMark = TimeSource.Monotonic.markNow()
                        lastClickPos = pos
                        when ((clickCount - 1) % 3) {
                            1 -> state.selectWordAt(pos)   // двойной клик — слово
                            2 -> state.selectLineAt(pos)   // тройной клик — строка
                            else -> {                       // одиночный — выделение перетаскиванием
                                state.beginSelection(pos)
                                val dragged = drag(down.id) { change ->
                                    change.consume()
                                    state.extendSelection(posAt(change.position.x, change.position.y))
                                }
                                if (!dragged || state.selection?.isEmpty != false) state.clearSelection()
                            }
                        }
                    } else {
                        // Тач: сначала — попал ли палец в маркер уже существующего выделения.
                        // Если да, перетаскиваем эту границу (вторую держим) — корректировка краёв,
                        // как в мессенджерах; по окончании обновляем меню «Copy».
                        val sel = state.selection
                        val handle = if (sel != null && !sel.isEmpty) {
                            // Якоря маркеров и down.position — в одной системе координат контента
                            // (pointerInput стоит после verticalScroll/padding), поэтому сравниваем напрямую.
                            val ds = (down.position - handleAnchor(sel.start)).getDistance()
                            val de = (down.position - handleAnchor(sel.end)).getDistance()
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

      // Курсор-оверлей поверх текста по форме DECSCUSR. Block — заливка ячейки + перерисовка
      // символа контрастным цветом; Underline — полоса снизу; Bar — вертикальная черта слева.
      // Геометрию берём по той же моноширинной метрике, что и текст, со сдвигом на прокрутку.
      if (cursorVisibleNow && state.screen.isNotEmpty()) {
          val thickness = with(density) { 2.dp.toPx() }
          val glyph = state.screen.getOrNull(state.cursorRow)?.getOrNull(state.cursorCol)?.text
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
              val x = state.cursorCol * metrics.cellWidth
              val y = state.cursorRow * metrics.cellHeight - scroll.value.toFloat()
              when (state.cursorShape) {
                  CursorShape.Block -> {
                      drawRect(cursorBg, topLeft = Offset(x, y), size = Size(metrics.cellWidth, metrics.cellHeight))
                      if (!glyph.isNullOrBlank()) {
                          drawText(measurer, glyph, topLeft = Offset(x, y), style = textStyle.copy(color = cursorFg))
                      }
                  }
                  CursorShape.Underline -> drawRect(
                      cursorBg,
                      topLeft = Offset(x, y + metrics.cellHeight - thickness),
                      size = Size(metrics.cellWidth, thickness),
                  )
                  CursorShape.Bar -> drawRect(
                      cursorBg,
                      topLeft = Offset(x, y),
                      size = Size(thickness, metrics.cellHeight),
                  )
              }
          }
      }

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
 * Цвет ФОНА ячейки для пер-клеточного оверлея (закраска полной ширины строки, включая хвостовые
 * пробелы). inverse → цвет текста (reverse-видео меняет fg/bg местами); заданный bg → его цвет;
 * дефолтный фон без inverse → `null` (рисовать не нужно — виден общий фон терминала). Подсветку
 * выделения накладывает сам оверлей отдельным слоем поверх фона.
 */
private fun cellBgColor(style: TermStyle): Color? = when {
    style.inverse -> style.fg.toComposeColor(SkerryColors.text)
    style.bg == TermColor.Default -> null
    else -> style.bg.toComposeColor(SkerryColors.text)
}

/**
 * [TextStyle] для отрисовки глифа ячейки: базовый моноширинный стиль + цвет/начертание/подчёркивание
 * из [TermStyle]. Фон убираем (его рисует оверлей отдельным слоем по полной ширине ячейки).
 */
private fun TermStyle.toGlyphStyle(base: TextStyle): TextStyle =
    base.merge(toSpanStyle().copy(background = Color.Unspecified))

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
    // Подчёркивание (включая modern 4:x формы и SGR 58 цвет) рисуем вручную в Canvas — Compose
    // TextDecoration не умеет ни волнистое/пунктирное/двойное, ни отдельный цвет. Здесь только
    // strikethrough, которое штатное.
    return SpanStyle(
        color = fgColor,
        background = bgColor,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
    )
}

/**
 * Цвет линии подчёркивания: [TermStyle.underlineColor], а при [TermColor.Default] — следует цвету
 * текста (с учётом inverse и dim). Рендерится отдельно от глифа, поэтому цвет считаем тут.
 */
private fun TermStyle.underlineDrawColor(): Color {
    val base = if (underlineColor == TermColor.Default) {
        if (inverse) {
            if (bg == TermColor.Default) SkerryColors.terminalBg else bg.toComposeColor(SkerryColors.text)
        } else fg.toComposeColor(SkerryColors.text)
    } else {
        underlineColor.toComposeColor(SkerryColors.text)
    }
    return if (dim) base.copy(alpha = 0.6f) else base
}

/**
 * Рисует линию подчёркивания нужной формы (modern SGR `4:x`) у нижней кромки ячейки/рана.
 * [left]/[width] — горизонтальный отрезок, [top] — верх строки, [chh] — высота клетки.
 */
private fun DrawScope.drawCellUnderline(style: TermStyle, left: Float, top: Float, width: Float, chh: Float) {
    if (style.underlineStyle == UnderlineStyle.None) return
    val color = style.underlineDrawColor()
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
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(thickness, thickness)),
            )
        UnderlineStyle.Dashed ->
            drawLine(
                color, Offset(left, y), Offset(right, y), strokeWidth = thickness,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(thickness * 4f, thickness * 3f)),
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
