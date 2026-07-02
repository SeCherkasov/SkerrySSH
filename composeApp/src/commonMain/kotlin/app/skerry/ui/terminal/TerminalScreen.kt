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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextRange
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import app.skerry.ui.theme.SkerryColors

/** Порог между кликами (мс), в пределах которого они складываются в двойной/тройной. */
private const val DOUBLE_CLICK_MS = 350

/** Полупериод мигания курсора (мс) — стандартный xterm-ритм ~530 мс на фазу. */
private const val CURSOR_BLINK_MS = 530L

private const val PADDING_DP = 14
// Сколько совпадений истории показывать в оверлее reverse-search (Ctrl-R) за раз.
private const val REVERSE_SEARCH_ROWS = 6

/** Радиус «капли» тач-маркера выделения и радиус зоны попадания пальца по нему. */
private const val HANDLE_RADIUS_DP = 7
private const val HANDLE_TOUCH_RADIUS_DP = 22

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
 * отправкой — мобильная клавишная панель пропускает через него sticky-ctrl ([app.skerry.ui.mobile.applyStickyCtrl]),
 * чтобы Ctrl+<буква> работал и с софт-клавиатуры, а не только с клавиш панели.
 */
@Composable
fun TerminalScreen(
    state: TerminalScreenState,
    modifier: Modifier = Modifier,
    imeInput: Boolean = false,
    imeTransform: ((String) -> String)? = null,
) {
    // Шрифт и кегль терминала — из настроек Appearance ([LocalTerminalAppearance]); дефолт Hack 13px
    // там, где провайдер не выставлен (мобильный таргет/превью/экран подключения). Лигатуры гасим
    // всегда ([NO_LIGATURES]), чтобы `->`/`=>`/`!=` не склеивались независимо от выбранного шрифта.
    val appearance = LocalTerminalAppearance.current
    // Цветовая тема терминала (Appearance → выбор темы): фон/текст/ANSI/акцент курсора. Меняется на
    // лету — все обращения ниже (фон Box, textStyle, cellBgColor, курсор, выделение) читают её.
    val termTheme = LocalTerminalTheme.current
    val cursorBg = termTheme.cursor
    val cursorFg = termTheme.cursorText
    val selectionBg = termTheme.selection
    val handleColor = termTheme.cursor
    val mono = rememberTerminalFontFamily(appearance.font)
    // Ключ — сам appearance (@Immutable data class, структурное равенство): меняется ровно при выборе
    // нового шрифта/кегля. FontFamily ключом ненадёжен — равенство двух собранных инстансов зависит от
    // equals у Font compose-resources, и при reference-equality textStyle/metrics инвалидировались бы
    // на каждой рекомпозиции. mono захватывается лямбдой и согласован с appearance.font.
    val textStyle = remember(appearance, termTheme) {
        TextStyle(
            fontFamily = mono,
            fontFeatureSettings = NO_LIGATURES,
            fontSize = appearance.fontSizeSp.sp,
            lineHeight = (appearance.fontSizeSp * appearance.lineHeight).sp,
            // Межбуквенный интервал входит в advance: cellWidth ниже меряется этим же стилем, поэтому
            // сетка (глифы/фон/курсор/мышь) остаётся согласованной при любом значении.
            letterSpacing = appearance.letterSpacingSp.sp,
            color = termTheme.foreground,
        )
    }
    val sessionState by state.state.collectAsState()
    val closed = sessionState is TerminalState.Closed
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    // Скрытое IME-поле (тач-ввод): держит фокус/клавиатуру, всегда сброшено к якорю.
    val imeFocusRequester = remember { FocusRequester() }
    val imeBaseline = remember { TextFieldValue(ANCHOR, selection = TextRange(ANCHOR.length)) }
    var imeValue by remember { mutableStateOf(imeBaseline) }
    // Системный буфер обмена — новый suspend-API ([androidx.compose.ui.platform.Clipboard]); чтение/запись
    // идут через clipboardScope (вызовы из не-suspend обработчиков клавиш/мыши — fire-and-forget).
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val textToolbar = LocalTextToolbar.current
    val uriHandler = LocalUriHandler.current
    // Контроллер софт-клавиатуры: на таче поднимаем её явно, т.к. requestFocus() на уже
    // сфокусированном скрытом поле — no-op (после скрытия фокус остаётся, клавиатура не всплывает).
    val keyboard = LocalSoftwareKeyboardController.current
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Подсчёт кликов мышью для двойного (слово) / тройного (строка) выделения: запоминаем время и
    // позицию предыдущего клика; повтор в той же ячейке за порог времени наращивает счётчик.
    // Ключ по state — при смене активной вкладки счётчик сбрасывается, иначе первый клик на новой
    // вкладке в той же позиции и в пределах порога ошибочно посчитался бы двойным/тройным.
    var clickCount by remember(state) { mutableStateOf(0) }
    var lastClickMark by remember(state) { mutableStateOf<TimeMark?>(null) }
    var lastClickPos by remember(state) { mutableStateOf<TerminalPos?>(null) }

    // Размер моноширинной ячейки в пикселях — единственный источник правды по геометрии: глифы,
    // фон, выделение, курсор, мышь и маркеры считаются от него арифметикой (col*cellWidth /
    // row*cellHeight), поэтому всё совпадает при любом шрифте и системном масштабе.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val metrics = remember(textStyle, density) {
        // cellWidth = РЕАЛЬНЫЙ advance шрифта, которым drawText раскладывает ASCII-раны. Меряем на длинной
        // строке и делим на её длину: size.width — целое (округление ~0.5px), на 10 символах это давало
        // ошибку до ~0.05px/символ, и к правому краю строки ASCII-ран уползал от cw-сетки на ~1 клетку
        // (подсветка/курсор/мышь считают по сетке, текст — по advance). На 200 символах ошибка ничтожна,
        // сетка совпадает с раскладкой drawText, дрейф исчезает.
        val sampleLen = 200
        val sample = measurer.measure(AnnotatedString("M".repeat(sampleLen)), textStyle)
        TerminalMetrics(
            cellWidth = sample.size.width / sampleLen.toFloat(),
            // cellHeight ОКРУГЛЯЕМ до целого пикселя: строки тайлятся встык (top = r*cellHeight), и при
            // дробной высоте (напр. множитель 1.38 → 13×1.38 = 17.94px, либо дробный масштаб дисплея)
            // границы соседних фон-прямоугольников попадают на дробные пиксели — Skia сглаживает стык, и
            // на однотонном фоне (панели mc) проступают горизонтальные «полосы» каждую строку. Целая
            // высота убирает шов. Ширину так округлять нельзя: там намеренно дробный advance (см. выше),
            // но высоту — можно: текст каждой строки рисуется независимо от её top, дрейф не копится.
            cellHeight = with(density) { textStyle.lineHeight.toPx() }.roundToInt().toFloat(),
        )
    }
    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val handleTouchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }

    // Активная сессия на desktop сразу забирает фокус (физическая клавиатура — печатаем без клика).
    // На таче ([imeInput]) фокус НЕ запрашиваем автоматически: иначе скрытое IME-поле поднимает
    // софт-клавиатуру прямо при подключении, и она подбрасывает раскладку/терминал вверх — видимый
    // «скачок». Клавиатура встаёт по тапу пользователя (обработчик жестов ниже, как Termius/Blink);
    // клавишная панель спецклавиш работает и без неё (шлёт в PTY напрямую).
    LaunchedEffect(state) { if (!closed && !imeInput) focusRequester.requestFocus() }

    // Автоскролл вниз по мере нового вывода. Следим И за snapshotVersion (новый вывод), И за самим
    // scroll.maxValue: высоту контента layout пересчитывает в фазе размещения, ПОЗЖЕ снимка, поэтому
    // читать maxValue прямо в момент снимка нельзя — он устаревший. Особенно при `clear`, где высота
    // резко меняется: по старому maxValue скролл уезжает мимо нового низа (виден старый текст — «экран
    // не очистился») или в пустоту. snapshotFlow до-эмитит уже пересчитанное значение, поэтому всегда
    // приземляемся на фактический низ (или на верх при сжатии контента).
    LaunchedEffect(state) {
        snapshotFlow { state.snapshotVersion to scroll.maxValue }
            .collect { (_, max) -> scroll.scrollTo(max) }
    }

    // OSC 52: приложение (tmux/vim) просит положить текст в системный буфер — кладём на UI-потоке.
    // (Запрос чтения буфера сервером эмулятор не пропускает — утечки буфера пользователя нет.)
    LaunchedEffect(state) {
        // try/catch на каждую запись: сбой одной копии (буфер недоступен) НЕ должен валить collect —
        // иначе OSC 52 перестанет работать до конца сессии. Отмену корутины пробрасываем.
        state.clipboardCopies.collect {
            try {
                // На Wayland пишем через wl-copy (тот же буфер, что читаем при вставке); иначе Compose.
                if (!withContext(Dispatchers.Default) { writeSystemClipboardDirect(it) }) {
                    clipboard.setClipEntry(plainTextClipEntry(it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // буфер недоступен/занят — копию молча теряем, collect продолжает работать
            }
        }
    }

    // Размер вьюпорта в ячейках → PTY/эмулятор: при первом лэйауте и ресайзе окна. Без этого
    // сетка остаётся дефолтной 80×24 и широкий вывод рвётся. state.resize гасит дубли сам.
    // Меряем ВНЕШНИЙ Box (вьюпорт), а не прокручиваемый Text: у того размер = высота всего контента.
    val paddingPx = with(density) { PADDING_DP.dp.toPx() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // Глифы/курсор не показываем, пока сетка не подогнана под вьюпорт хотя бы раз: иначе первый вывод
    // шелла лёг бы на дефолтные 80×24, а затем «переукладывался» при ресайзе — видимый «подпрыг»
    // текста при открытии. До этого виден только фон терминала (тот же цвет), потом — готовый перенос.
    var sized by remember(state) { mutableStateOf(false) }
    // state в ключах ОБЯЗАТЕЛЕН: при переключении вкладок меняется только state (новый
    // TerminalScreenState активной сессии), а viewportSize/metrics/paddingPx остаются прежними.
    // sized сбрасывается в false (remember(state) выше), и без state-ключа этот эффект не
    // перезапустился бы — resize не вызвался, sized навсегда остался бы false, и сетка/курсор
    // не рисовались бы (вывод «пропадает» при переключении между вкладками).
    LaunchedEffect(state, viewportSize, metrics, paddingPx) {
        if (viewportSize.width == 0 || viewportSize.height == 0) return@LaunchedEffect
        // Дебаунс ресайза: при анимации софт-клавиатуры (adjustResize) вьюпорт меняется каждый кадр;
        // без задержки PTY ресайзился бы на каждый промежуточный размер → сетка переукладывается и
        // текст «мигает/прыгает и возвращается». Новое изменение размера перезапускает эффект и
        // отменяет ждущий ресайз, поэтому срабатываем один раз — когда размер устаканился. Самый
        // первый ресайз (sized=false, открытие терминала) делаем мгновенно, без задержки.
        if (sized) delay(150)
        state.resize(gridSizeFor(viewportSize.width.toFloat(), viewportSize.height.toFloat(), paddingPx, metrics))
        sized = true
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
    val cursorVisibleNow = sized && !closed && state.cursorVisible && blinkOn

    // Единый снимок состояния на рекомпозицию: и текстовый оверлей, и курсорный оверлей читают одни и
    // те же screen/cursor — иначе между двумя draw-проходами снимок мог бы разъехаться (курсор по новой
    // позиции на старой сетке). Compose перерисует оба при следующей публикации (snapshotVersion).
    val screen = state.screen
    val cursorRow = state.cursorRow
    val cursorCol = state.cursorCol

    // Нижний «слак» прокрутки: сетка из rows строк короче вьюпорта на остаток деления его высоты на
    // строку (floor в gridSizeFor). Без компенсации при прокрутке вниз в этот зазор сверху подглядывает
    // последняя строка scrollback — после `clear` это та самая строка с командой, которая должна уйти
    // вверх. Добавляем пустой зазор ПОД контентом: тогда maxValue == высоте scrollback, и живая сетка
    // приклеивается к верху вьюпорта (история прячется вверх, как в настоящем терминале).
    val bottomSlack = with(density) {
        (viewportSize.height - 2 * paddingPx - state.rows * metrics.cellHeight).coerceAtLeast(0f).toDp()
    }

    // Высота прокручиваемого контента ЗАДАЁТСЯ ЯВНО в пикселях (число строк × cellHeight), а НЕ числом
    // строк невидимого Text: реальная высота строки Text на разных платформах (Compose/Skia на desktop
    // vs Android) может расходиться с cellHeight, по которому рисует Canvas. На длинном scrollback дрейф
    // копится и сбивает maxValue ≈ на строку — тогда прокрутка вниз недокручивает и сверху подглядывает
    // строка scrollback (после `clear` — строка с командой). От метрик шрифта так не зависим.
    val contentHeight = with(density) { (screen.size * metrics.cellHeight).toDp() }
    // Сам Text-подложка невидим: задаёт зону ввода/IME и фокус — глифы рисует отдельный оверлей (ниже).
    val structural = remember { AnnotatedString("") }

    // Кэш TextStyle глифа по TermStyle: toGlyphStyle делает merge() (аллокация SpanStyle+TextStyle) на
    // каждый ран. Строк/ранов на кадр — сотни, но различных стилей мало, поэтому мемоизируем. Сбрасываем
    // при смене базового стиля или палитры (OSC 4/104) — от них зависит результат.
    val glyphStyleCache = remember(textStyle, state.palette, termTheme) { HashMap<TermStyle, TextStyle>() }

    // PathEffect для пунктирного/штрихового подчёркивания зависит только от высоты клетки (константа при
    // фиксированном шрифте) — считаем один раз, а не на каждый подчёркнутый ран в draw-фазе.
    val underlineEffects = remember(metrics) {
        val t = (metrics.cellHeight / 14f).coerceAtLeast(1f)
        UnderlineEffects(
            dotted = PathEffect.dashPathEffect(floatArrayOf(t, t)),
            dashed = PathEffect.dashPathEffect(floatArrayOf(t * 4f, t * 3f)),
        )
    }

    fun cellAt(x: Float, y: Float) = cellAtOffset(x, y, metrics)

    // Координата указателя → ячейка чистой арифметикой: pointerInput стоит после verticalScroll и
    // padding, поэтому offset уже в координатах контента (со скроллом, без отступа). Колонку/строку
    // поджимаем к фактической сетке, чтобы выделение не уходило за её пределы.
    fun posAt(x: Float, y: Float): TerminalPos {
        val p = cellAt(x, y)
        // Свежий снимок (жесты идут вне рекомпозиции), не захваченный composition-локал.
        val snap = state.screen
        if (snap.isEmpty()) return p
        val row = p.row.coerceIn(0, snap.lastIndex)
        return TerminalPos(row, p.col.coerceIn(0, snap[row].size))
    }

    // Репорт мыши приложению по координатам указателя. Пиксели берём из той же системы координат
    // контента, что и posAt (после verticalScroll/padding), и передаём отдельно — encodeMouseReport
    // использует их лишь в SGR-Pixels (1016), в клеточных режимах они игнорируются.
    fun reportMouseAt(
        button: MouseButton,
        type: MouseEventType,
        x: Float,
        y: Float,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ): Boolean = state.reportMouse(
        button, type, posAt(x, y), shift, alt, ctrl,
        x.toInt().coerceAtLeast(0), y.toInt().coerceAtLeast(0),
    )

    // Якорь границы выделения в координатах контента (нижний угол ячейки) — арифметика, совпадающая
    // с сеткой глифов. Канвас маркеров рисует с поправкой на прокрутку.
    fun handleAnchor(pos: TerminalPos): Offset =
        Offset(pos.col * metrics.cellWidth, (pos.row + 1) * metrics.cellHeight)

    // try/catch на каждую буферную корутину: scope из rememberCoroutineScope несёт обычный Job (не
    // Supervisor), поэтому необработанное исключение в одной операции отменило бы весь scope и убило бы
    // копирование/вставку до конца сессии. Отмену корутины пробрасываем, прочее глушим (буфер недоступен).
    fun copySelection() {
        val text = state.selectedText() ?: return
        clipboardScope.launch {
            try {
                // Wayland: wl-copy (парно к вставке через wl-paste); иначе штатный Compose-буфер.
                if (!withContext(Dispatchers.Default) { writeSystemClipboardDirect(text) }) {
                    clipboard.setClipEntry(plainTextClipEntry(text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Текст системного CLIPBOARD. На Wayland (прямой путь берёт чтение на себя) читаем через wl-paste,
    // минуя AWT, — и НЕ откатываемся на Compose даже при пустом результате, иначе при не-текстовом
    // буфере всплыла бы шумная JDK-трасса. Субпроцесс и резолв утилит держим вне UI-потока (Default).
    suspend fun fetchClipboardText(): String? =
        // Гейт и субпроцесс — одним заходом на Default; getClipEntry (suspend, ждёт UI-поток) — на
        // возвращённом контексте вызывающего (Main). Прямой путь, взяв чтение, на AWT уже не падает.
        withContext(Dispatchers.Default) {
            if (systemClipboardDirectHandlesReads()) readSystemClipboardDirect() else null
        } ?: if (systemClipboardDirectHandlesReads()) null else clipboard.getClipEntry()?.readPlainText()

    // Вставка из системного буфера: читаем асинхронно и шлём текст в PTY (paste сам оборачивает
    // bracketed-paste, если приложение включило). Пустой/нетекстовый буфер — no-op.
    fun pasteFromClipboard() {
        clipboardScope.launch {
            try {
                fetchClipboardText()?.let { state.paste(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Средний клик = вставка PRIMARY: системный PRIMARY (X11 AWT / Wayland wl-paste, видит выделение
    // в других окнах) → in-app буфер → CLIPBOARD. Чтение PRIMARY на Wayland — субпроцесс, поэтому весь
    // флоу уводим в корутину (Default для чтения, paste обратно на UI), чтобы не блокировать клик.
    fun pastePrimaryOrClipboard() {
        clipboardScope.launch {
            try {
                val primary = withContext(Dispatchers.Default) { readPrimarySelectionText() }
                    ?.takeUnless { it.isBlank() }
                    ?: state.primarySelection?.takeUnless { it.isBlank() }
                if (primary != null) state.paste(primary) else fetchClipboardText()?.let { state.paste(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Публикуем завершённое выделение мышью как PRIMARY: в системный PRIMARY (X11 AWT / Wayland wl-copy)
    // и в in-app буфер. Тогда средний клик вставляет именно выделенное. Запись PRIMARY на Wayland —
    // субпроцесс, поэтому уводим в корутину (Default), чтобы не блокировать UI. No-op, если выделять нечего.
    fun publishPrimary() {
        val text = state.capturePrimarySelection() ?: return
        clipboardScope.launch {
            try {
                withContext(Dispatchers.Default) { writePrimarySelectionText(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
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

    Box(modifier.onSizeChanged { viewportSize = it }.background(termTheme.background)) {
      // Весь видимый экран рисуем ОДНИМ пер-клеточным оверлеем по моноширинной сетке (а не потоковым
      // Text): фон ячеек на полную ширину строки (включая хвостовые reverse-пробелы TUI), подсветку
      // выделения и сами глифы — каждый по своей колонке `col*cellWidth`. Так широкие символы (CJK,
      // emoji) и их continuation-клетки держат сетку, а курсор/мышь/маркеры (та же арифметика) с ней
      // совпадают. Геометрия как у курсора: тот же padding и сдвиг на прокрутку. Сам Text ниже —
      // невидимая подложка (высота для скролла + приём ввода).
      if (sized && screen.isNotEmpty()) {
          val sel = state.selection
          val palette = state.palette // OSC 4/104 переопределения индексов; пусто — дефолты темы
          // clipToBounds ПОСЛЕ padding: строка scrollback на границе прокрутки рисуется с top=-chh и
          // иначе вылезала бы в зону верхнего паддинга (на desktop клипа по умолчанию нет, в отличие от
          // Android) — после `clear` так подглядывала строка с командой. Клип режет её по краю контента.
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp).clipToBounds()) {
              val scrollPx = scroll.value.toFloat()
              val cw = metrics.cellWidth
              val chh = metrics.cellHeight
              for (r in screen.indices) {
                  val top = r * chh - scrollPx
                  if (top + chh < 0f || top > size.height) continue
                  val row = screen[r]
                  // 1) Фон ячеек — раны одного цвета схлопываем; хвостовой ран тянем до края вьюпорта.
                  var c = 0
                  while (c < row.size) {
                      val color = cellBgColor(row[c].style, palette, termTheme)
                      if (color == null) { c++; continue }
                      val s = c; c++
                      while (c < row.size && cellBgColor(row[c].style, palette, termTheme) == color) c++
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
                  // 3) Глифы — сегментируем строку на раны (см. glyphRuns): подряд идущие
                  // одностилевые ASCII-клетки рисуем одним drawText (быстрый моноширинный случай),
                  // а каждый НЕ-ASCII глиф (box-drawing рамок mc, CJK, символы) — в свою колонку
                  // отдельно: fallback-шрифт даёт не-cellWidth advance, и длинный ран накапливал бы
                  // дрейф (рваные горизонтали рамок, съезд цветных строк). Wide-клетка — span=2.
                  for (run in glyphRuns(row)) {
                      val x = run.col * cw
                      if (run.text.isNotBlank()) {
                          val style = glyphStyleCache.getOrPut(run.style) { run.style.toGlyphStyle(textStyle, palette, termTheme) }
                          drawText(measurer, run.text, topLeft = Offset(x, top), style = style)
                      }
                      // Подчёркивание тянем по всей ширине рана, в т.ч. под пробелами (как в xterm).
                      if (run.style.underline) drawCellUnderline(run.style, x, top, run.span * cw, chh, palette, underlineEffects, termTheme)
                  }
                  // 4) Гиперссылки (OSC 8) подчёркиваем отдельным проходом — раны соседних клеток с
                  // одним URI; пропускаем те, что уже подчёркнуты приложением (SGR), чтобы не дублировать.
                  var h = 0
                  while (h < row.size) {
                      val uri = row[h].hyperlink
                      if (uri == null) { h++; continue }
                      val from = h
                      while (h < row.size && row[h].hyperlink == uri) h++
                      val to = h
                      var k = from
                      while (k < to) {
                          if (row[k].style.underline) { k++; continue } // app уже подчёркивает — не дублируем
                          val runStart = k
                          while (k < to && !row[k].style.underline) k++
                          drawCellUnderline(LINK_UNDERLINE_STYLE, runStart * cw, top, (k - runStart) * cw, chh, palette, underlineEffects, termTheme)
                      }
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
            // bottom += bottomSlack — пустой зазор под контентом, чтобы живая сетка приклеивалась к
            // верху вьюпорта, а не оставляла сверху подсмотренную строку scrollback (см. bottomSlack).
            .padding(start = PADDING_DP.dp, top = PADDING_DP.dp, end = PADDING_DP.dp, bottom = PADDING_DP.dp + bottomSlack)
            // Высота прокрутки задаётся явно (contentHeight) от cellHeight, а не метрик шрифта Text.
            .height(contentHeight)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            // Focus reporting (DEC 1004): vim/tmux получают ESC[I/ESC[O при фокусе окна терминала.
            .onFocusChanged { state.notifyFocus(it.isFocused) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || closed) return@onPreviewKeyEvent false
                // --- Reverse-search истории (Ctrl-R): пока оверлей открыт, клавиши правят его, не PTY ---
                if (state.reverseSearchQuery != null) {
                    when {
                        event.key == Key.Escape -> state.closeReverseSearch()
                        event.key == Key.Enter -> state.reverseSearchAccept()
                        // Ещё один Ctrl-R (или ↑) — к следующему (более старому) совпадению; ↓ — к новее.
                        (event.isCtrlPressed && event.key == Key.R) || event.key == Key.DirectionUp ->
                            state.reverseSearchNext()
                        event.key == Key.DirectionDown -> state.reverseSearchPrev()
                        // Delete — убрать выбранную команду из истории (ручная чистка), оверлей открыт.
                        event.key == Key.Delete -> state.reverseSearchDeleteSelected()
                        event.key == Key.Backspace -> state.reverseSearchBackspace()
                        else -> {
                            val cp = event.utf16CodePoint
                            if (cp in 0x20..0xFFFF && !event.isCtrlPressed && !event.isAltPressed) {
                                state.reverseSearchAppend(cp.toChar().toString())
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // Ctrl-R — открыть reverse-search истории (перехват у shell, показываем свой оверлей).
                if (event.isCtrlPressed && event.key == Key.R) {
                    state.openReverseSearch()
                    return@onPreviewKeyEvent true
                }
                // Shift+Tab — циклировать альтернативы подсказки автодополнения (только если она есть).
                if (event.isShiftPressed && event.key == Key.Tab && state.suggestionTail != null) {
                    state.cycleSuggestion()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+Shift+C — копирование выделения (Ctrl+C остаётся SIGINT для shell).
                if (event.isCtrlPressed && event.isShiftPressed && event.key == Key.C) {
                    copySelection()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+Shift+V и Shift+Insert — вставка из буфера обмена (bracketed-paste, если приложение включило).
                if ((event.isCtrlPressed && event.isShiftPressed && event.key == Key.V) ||
                    (event.isShiftPressed && event.key == Key.Insert)
                ) {
                    pasteFromClipboard()
                    return@onPreviewKeyEvent true
                }
                val bytes = mapTerminalKey(
                    key = event.key,
                    ctrl = event.isCtrlPressed,
                    codePoint = event.utf16CodePoint,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    applicationCursor = state.applicationCursorKeys,
                    applicationKeypad = state.applicationKeypad,
                )
                if (bytes != null) {
                    state.clearSelection()
                    textToolbar.hide()
                    // Tab при наличии подсказки автодополнения — принять её (fish-style), не слать в shell.
                    // Без подсказки Tab уходит в PTY как обычно (серверное completion не ломается).
                    if (bytes == "\t" && state.suggestionTail != null) {
                        state.acceptSuggestion()
                    } else {
                        state.typeInput(bytes)
                    }
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
                                reportMouseAt(if (up) MouseButton.WheelUp else MouseButton.WheelDown, MouseEventType.Press, change.position.x, change.position.y)
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
            // внутри encodeMouseReport). Движение с ЗАЖАТОЙ кнопкой — это Drag, его репортят жестовые
            // циклы ниже (левая — awaitEachGesture, средняя/правая — сырой обработчик), поэтому здесь
            // глушим события с любой зажатой кнопкой, иначе один кадр движения уйдёт и как Move, и как Drag.
            .pointerInput(state, closed) {
                var lastHover: TerminalPos? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (closed || state.mouseTracking != MouseTracking.AnyEvent) continue
                        if (event.type != PointerEventType.Move || event.buttons.areAnyPressed) continue
                        val change = event.changes.firstOrNull { !it.pressed } ?: continue
                        val pos = posAt(change.position.x, change.position.y)
                        if (pos != lastHover) {
                            reportMouseAt(MouseButton.Left, MouseEventType.Move, change.position.x, change.position.y)
                            lastHover = pos
                        }
                    }
                }
            }
            // Средняя/правая кнопки мыши: awaitFirstDown (в жесте ниже) реагирует только на основную
            // (левую) кнопку, поэтому средний/правый клик ловим здесь на сырых событиях. При активном
            // mouse-tracking — репортим press/release приложению; иначе средний = вставка (PRIMARY-
            // выделение X11 c откатом на буфер), правый = КОПИРОВАТЬ текущее выделение в буфер.
            .pointerInput(state, closed) {
                var reported: MouseButton? = null
                var lastPos: TerminalPos? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                        if (closed) continue
                        val mods = event.keyboardModifiers
                        val shift = mods.isShiftPressed
                        val reporting = state.mouseTracking != MouseTracking.Off && !shift
                        val x = change.position.x
                        val y = change.position.y
                        val pos = posAt(x, y)
                        when (event.type) {
                            PointerEventType.Press -> {
                                val btn = when {
                                    event.buttons.isTertiaryPressed -> MouseButton.Middle
                                    event.buttons.isSecondaryPressed -> MouseButton.Right
                                    else -> null
                                } ?: continue
                                if (reporting) {
                                    reportMouseAt(btn, MouseEventType.Press, x, y, shift, mods.isAltPressed, mods.isCtrlPressed)
                                    reported = btn
                                    lastPos = pos
                                } else if (btn == MouseButton.Middle) {
                                    // Средний клик = вставка PRIMARY (PRIMARY → in-app буфер → CLIPBOARD).
                                    // Чтение PRIMARY на Wayland — субпроцесс, поэтому весь флоу асинхронный.
                                    pastePrimaryOrClipboard()
                                } else {
                                    // Правый клик = копировать текущее выделение в буфер обмена (без
                                    // вставки — вставка осталась на средней кнопке). No-op, если ничего
                                    // не выделено. Выделение НЕ снимаем, чтобы можно было копировать повторно.
                                    copySelection()
                                }
                                change.consume()
                            }
                            // Drag средней/правой кнопки (DEC 1002/1003): awaitFirstDown в жесте ниже
                            // реагирует только на основную кнопку, поэтому движение с зажатой средней/правой
                            // отслеживаем здесь — репортим только при смене ячейки, чтобы не спамить.
                            PointerEventType.Move -> reported?.let { btn ->
                                // Если приложение выключило трекинг (или зажат Shift) на середине drag —
                                // больше не репортим, но событие всё равно глушим, пока кнопку не отпустят.
                                if (reporting && pos != lastPos) {
                                    reportMouseAt(btn, MouseEventType.Drag, x, y, shift, mods.isAltPressed, mods.isCtrlPressed)
                                    lastPos = pos
                                }
                                change.consume()
                            }
                            PointerEventType.Release -> reported?.let { btn ->
                                if (reporting) {
                                    reportMouseAt(btn, MouseEventType.Release, x, y, shift, mods.isAltPressed, mods.isCtrlPressed)
                                }
                                reported = null
                                lastPos = null
                                change.consume()
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput(state, metrics) {
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
                            reportMouseAt(MouseButton.Left, MouseEventType.Press, down.position.x, down.position.y, shift, alt, ctrl)
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
                                        reportMouseAt(MouseButton.Left, MouseEventType.Drag, change.position.x, change.position.y, shift, alt, ctrl)
                                        last = pos
                                    }
                                    change.consume()
                                } else {
                                    reportMouseAt(MouseButton.Left, MouseEventType.Release, change.position.x, change.position.y, shift, alt, ctrl)
                                    change.consume()
                                    break
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Локальное выделение. Счётчик кликов: 1 — drag-выделение, 2 — слово, 3 — строка.
                        val pos = posAt(down.position.x, down.position.y)
                        // Ctrl+клик по клетке с OSC 8-гиперссылкой — открыть URI (не начинать выделение).
                        // URI приходит от НЕДОВЕРЕННОГО сервера — открываем лишь безопасные веб-схемы,
                        // отсекая file:/javascript:/прочее, что могло бы навредить локально.
                        if (mods.isCtrlPressed) {
                            val uri = state.screen.getOrNull(pos.row)?.getOrNull(pos.col)?.hyperlink
                            if (uri != null && isSafeLinkUri(uri)) {
                                runCatching { uriHandler.openUri(uri) }
                                down.consume()
                                return@awaitEachGesture
                            }
                        }
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
                        // Завершённое выделение мышью становится PRIMARY — источник для среднего клика.
                        publishPrimary()
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
                            if (state.selection?.isEmpty != false) state.clearSelection() else { showCopyMenu(); publishPrimary() }
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
                            if (state.selection?.isEmpty != false) state.clearSelection() else { showCopyMenu(); publishPrimary() }
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
      if (cursorVisibleNow && screen.isNotEmpty()) {
          val thickness = with(density) { 2.dp.toPx() }
          val glyph = screen.getOrNull(cursorRow)?.getOrNull(cursorCol)?.text
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
              val x = cursorCol * metrics.cellWidth
              val y = cursorRow * metrics.cellHeight - scroll.value.toFloat()
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

      // «Призрак» автодополнения (Phase 3): серым дорисовываем хвост подсказки от позиции курсора
      // (fish/zsh-стиль). Та же моноширинная геометрия, что у курсора; Tab (desktop) / чип (mobile)
      // принимают его. В alt-screen (vim/htop) подсказки нет — [suggestionTail] там уже сброшен.
      val ghost = state.suggestionTail
      if (ghost != null && !closed && screen.isNotEmpty()) {
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp).clipToBounds()) {
              val x = cursorCol * metrics.cellWidth
              val y = cursorRow * metrics.cellHeight - scroll.value.toFloat()
              drawText(measurer, ghost, topLeft = Offset(x, y), style = textStyle.copy(color = Color(0x66E6F2FA)))
          }
      }

      // Оверлей reverse-search истории (Ctrl-R): панель снизу с текущим запросом и совпадениями
      // (выбранное — cyan). Enter вставляет, Esc закрывает, повторный Ctrl-R/стрелки листают.
      val rsQuery = state.reverseSearchQuery
      if (rsQuery != null && !closed) {
          val matches = state.reverseSearchResults
          val shown = matches.take(REVERSE_SEARCH_ROWS)
          Column(
              Modifier
                  .align(Alignment.BottomStart)
                  .fillMaxWidth()
                  .background(Color(0xF00A1620))
                  .padding(horizontal = 10.dp, vertical = 6.dp),
          ) {
              Text(
                  text = "(reverse-i-search)`$rsQuery`:  ↑↓ выбрать · Enter вставить · Del убрать · Esc",
                  style = textStyle.copy(color = Color(0xFF8AA0AE)),
              )
              if (shown.isEmpty()) {
                  Text("— нет совпадений —", style = textStyle.copy(color = Color(0xFF5B6B77)))
              } else {
                  shown.forEachIndexed { i, cmd ->
                      val selected = i == state.reverseSearchIndex.mod(matches.size.coerceAtLeast(1))
                      Text(
                          text = cmd,
                          maxLines = 1,
                          style = textStyle.copy(
                              color = if (selected) SkerryColors.cyan else Color(0xFFB8C6D0),
                              fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                          ),
                      )
                  }
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
                  drawSelectionHandle(startAnchor.copy(y = startAnchor.y + dy), handleRadiusPx, metrics.cellHeight, SelectionHandle.START, handleColor)
                  drawSelectionHandle(endAnchor.copy(y = endAnchor.y + dy), handleRadiusPx, metrics.cellHeight, SelectionHandle.END, handleColor)
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
                      // Пока открыт reverse-search — софт-клавиатура правит запрос, а не PTY:
                      // DEL → backspace, Enter(CR) → принять, печатные символы → в запрос.
                      if (state.reverseSearchQuery != null) {
                          for (ch in out) when (ch.code) {
                              127, 8 -> state.reverseSearchBackspace() // DEL / BS
                              13, 10 -> state.reverseSearchAccept() // CR / LF — принять
                              else -> if (ch.code >= 0x20) state.reverseSearchAppend(ch.toString())
                          }
                      } else {
                          state.typeInput(out) // питает автодополнение (софт-клавиатура), затем в PTY
                      }
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
 * Цвет ФОНА ячейки для пер-клеточного оверлея (закраска полной ширины строки, включая хвостовые
 * пробелы). inverse → цвет текста (reverse-видео меняет fg/bg местами); заданный bg → его цвет;
 * дефолтный фон без inverse → `null` (рисовать не нужно — виден общий фон терминала). Подсветку
 * выделения накладывает сам оверлей отдельным слоем поверх фона.
 */
private fun cellBgColor(style: TermStyle, palette: Palette, theme: TerminalTheme): Color? = when {
    style.inverse -> style.fg.toComposeColor(theme, palette)
    style.bg == TermColor.Default -> null
    else -> style.bg.toComposeColor(theme, palette)
}

/**
 * Один ран глифов для отрисовки: текст, начальная колонка [col], число занятых колонок [span] (для
 * подчёркивания: Wide=2, ASCII-ран = число клеток) и стиль.
 */
internal data class GlyphRun(val col: Int, val text: String, val span: Int, val style: TermStyle)

/**
 * Печатный ASCII (один BMP-символ 0x20..0x7e) — у JetBrains Mono гарантированно cellWidth advance.
 * Вызывается только для Single-клеток (Continuation/Wide отфильтрованы раньше в [glyphRuns]).
 */
private fun TermCell.isPlainAscii(): Boolean = text.length == 1 && text[0].code in 0x20..0x7e

/**
 * Сегментирует строку сетки на раны глифов. Подряд идущие одностилевые ASCII-клетки склеиваются в
 * один ран (быстрый моноширинный drawText), а каждый НЕ-ASCII глиф (box-drawing рамок mc, CJK,
 * символы) выделяется в отдельный ран на одну колонку — потому что fallback-шрифт рисует такие глифы
 * с advance ≠ cellWidth, и в длинном ране это копит дрейф (рваные горизонтали рамок, съезд цветных
 * строк на колонку). Wide-клетка — отдельный ран на две колонки; Continuation глифа не несёт.
 * Колонка рана — физический индекс клетки, поэтому Continuation-«дыра» не сдвигает следующий ран.
 */
internal fun glyphRuns(row: List<TermCell>): List<GlyphRun> {
    val runs = ArrayList<GlyphRun>()
    var g = 0
    while (g < row.size) {
        val cell = row[g]
        when {
            cell.width == CellWidth.Continuation -> g++
            cell.width == CellWidth.Wide -> {
                runs.add(GlyphRun(g, cell.text, 2, cell.style)); g++
            }
            !cell.isPlainAscii() -> {
                runs.add(GlyphRun(g, cell.text, 1, cell.style)); g++
            }
            else -> {
                val st = cell.style
                val start = g
                val sb = StringBuilder()
                while (g < row.size && row[g].width == CellWidth.Single && row[g].style == st && row[g].isPlainAscii()) {
                    sb.append(row[g].text); g++
                }
                runs.add(GlyphRun(start, sb.toString(), g - start, st))
            }
        }
    }
    return runs
}

/**
 * [TextStyle] для отрисовки глифа ячейки: базовый моноширинный стиль + цвет/начертание/подчёркивание
 * из [TermStyle]. Фон убираем (его рисует оверлей отдельным слоем по полной ширине ячейки).
 */
private fun TermStyle.toGlyphStyle(base: TextStyle, palette: Palette, theme: TerminalTheme): TextStyle =
    base.merge(toSpanStyle(palette, theme).copy(background = Color.Unspecified))

private fun TermStyle.toSpanStyle(palette: Palette, theme: TerminalTheme): SpanStyle {
    // inverse меняет местами текст и фон; при дефолтном фоне он становится цветом фона терминала.
    val resolvedFg = fg.toComposeColor(theme, palette)
    val resolvedBg = if (bg == TermColor.Default) theme.background else bg.toComposeColor(theme, palette)
    var fgColor = if (inverse) resolvedBg else resolvedFg
    val bgColor = when {
        inverse -> resolvedFg
        bg == TermColor.Default -> Color.Unspecified
        else -> resolvedBg
    }
    if (hidden) fgColor = bgColor.takeIf { it != Color.Unspecified } ?: theme.background
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

/** Переопределения палитры (OSC 4/104): индекс 0..255 → Rgb. Пусто — используются дефолты темы. */
private typealias Palette = Map<Int, TermColor.Rgb>

/** Предвычисленные PathEffect для пунктирного/штрихового подчёркивания (зависят лишь от высоты клетки). */
private data class UnderlineEffects(val dotted: PathEffect, val dashed: PathEffect)

/** Стиль подчёркивания OSC 8-гиперссылок: одиночная линия тематическим бирюзовым (primary cyan). */
private val LINK_UNDERLINE_STYLE = TermStyle(
    underlineStyle = UnderlineStyle.Single,
    underlineColor = TermColor.Rgb(0x2B, 0xBD, 0xEE),
)

/**
 * Цвет линии подчёркивания: [TermStyle.underlineColor], а при [TermColor.Default] — следует цвету
 * текста (с учётом inverse и dim). Рендерится отдельно от глифа, поэтому цвет считаем тут.
 */
private fun TermStyle.underlineDrawColor(palette: Palette, theme: TerminalTheme): Color {
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
 * Рисует линию подчёркивания нужной формы (modern SGR `4:x`) у нижней кромки ячейки/рана.
 * [left]/[width] — горизонтальный отрезок, [top] — верх строки, [chh] — высота клетки.
 */
private fun DrawScope.drawCellUnderline(style: TermStyle, left: Float, top: Float, width: Float, chh: Float, palette: Palette, effects: UnderlineEffects, theme: TerminalTheme) {
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
 * Перевод [TermColor] в Compose Color: Default — цвет из контекста; Rgb — напрямую; Indexed —
 * xterm-палитра, где первые 16 индексов взяты из активной темы ([TerminalTheme.ansi]), а 16..255 — стандартный
 * 6×6×6 куб и градации серого.
 */
private fun TermColor.toComposeColor(theme: TerminalTheme, palette: Palette): Color = when (this) {
    TermColor.Default -> theme.foreground
    is TermColor.Rgb -> Color(r, g, b)
    is TermColor.Indexed -> xtermColor(index, palette, theme)
}

/** ANSI 0..15 из активной темы + стандартный xterm-куб/grayscale для 16..255; OSC 4-override приоритетнее. */
private fun xtermColor(index: Int, palette: Palette, theme: TerminalTheme): Color {
    palette[index]?.let { return Color(it.r, it.g, it.b) }
    if (index in 0..15) return theme.ansi[index]
    return xtermDefaultColor(index)
}

/** Дефолтная xterm-палитра темы (без OSC 4-переопределений). */
private fun xtermDefaultColor(index: Int): Color = when (index) {
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
