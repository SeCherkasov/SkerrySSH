package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.AutocompleteEngine
import app.skerry.shared.terminal.CommandHistory
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.DEFAULT_MAX_SCROLLBACK
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TerminalEmulator
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.bracketedPasteWrap
import app.skerry.shared.terminal.encodeMouseReport
import app.skerry.shared.terminal.lineSelectionAt
import app.skerry.shared.terminal.wordSelectionAt
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Состояние терминального экрана поверх [TerminalSession]. Сырые байты PTY проходят через
 * [TerminalEmulator] (парсер ANSI/VT + модель экрана), результат публикуется как [screen] —
 * сетка ячеек с цветом/жирностью — плюс позиция курсора. Ввод и ресайз проксируются в сессию.
 *
 * Эмулятор держит scrollback и парсер-состояние сам, поэтому здесь нет ни сырого байтового буфера,
 * ни ручного декода UTF-8: каждый чанк просто скармливается, а снимок экрана кладётся в Compose-
 * state ([screen]/[cursorRow]/[cursorCol]) для перерисовки.
 */
@Stable
class TerminalScreenState(
    private val session: TerminalSession,
    private val scope: CoroutineScope,
    // История команд для автодополнения, предзагруженная для этого хоста (от новой к старой), и колбэк
    // персиста при каждой закоммиченной команде. По умолчанию пусто/без персиста — сессия учится
    // только на себе (поведение до Phase 3.1). Персист идёт только с эхом (пароли отсекаются выше).
    initialHistory: List<String> = emptyList(),
    private val onHistoryChanged: ((List<String>) -> Unit)? = null,
    // Настройки терминала (Settings → Терминал), применяемые к НОВОЙ сессии: глубина scrollback и
    // форма/мигание курсора по умолчанию. Дефолты сохраняют прежнее поведение (5000 строк, блок+мигание).
    scrollback: Int = DEFAULT_MAX_SCROLLBACK,
    cursorShape: CursorShape = CursorShape.Block,
    cursorBlink: Boolean = true,
) {
    // Запросы OSC 52 на запись в системный буфер. extraBufferCapacity — чтобы tryEmit с
    // корутины-владельца не терялся без подписчика в момент эмита; DROP_OLDEST при всплеске
    // оставляет последнюю запись (last-writer-wins, верная семантика буфера), а не старую.
    private val _clipboardCopies = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Текст, который приложение просит положить в системный буфер (OSC 52). UI собирает и кладёт. */
    val clipboardCopies: SharedFlow<String> = _clipboardCopies

    private val emulator = TerminalEmulator(
        maxScrollback = scrollback,
        initialCursorShape = cursorShape,
        initialCursorBlink = cursorBlink,
        // respond: ответы терминала (DSR/DA) уходят обратно в PTY, иначе приложения, опрашивающие
        // курсор/атрибуты, подвисают. Колбэк зовётся синхронно из feed() (корутина-владелец), поэтому
        // обязан лишь писать в PTY (send → session.send) и НЕ заводить новый feed/resize — иначе
        // ломается однопоточный контракт эмулятора. session.send в PTY-сток, в commands не возвращается.
        respond = { reply -> send(reply) },
        // OSC 52-запись зовётся так же синхронно из feed() — буфер здесь не трогаем, публикуем во flow,
        // а композбл кладёт в системный буфер на UI-потоке.
        onClipboardCopy = { text -> _clipboardCopies.tryEmit(text) },
    )

    /** Снимок экрана (строки сверху вниз) для отрисовки. */
    var screen: List<List<TermCell>> by mutableStateOf(emptyList())
        private set

    /**
     * Монотонный счётчик публикаций снимка — растёт на каждый feed/resize, даже если содержимое [screen]
     * структурно совпало с прежним. Авто-скролл к низу должен запускаться по нему, а НЕ по [screen]:
     * Compose сравнивает список структурно ([equals]), и два подряд одинаковых снимка не дёрнут эффект.
     */
    var snapshotVersion: Int by mutableStateOf(0)
        private set

    /** Текущий размер сетки (живой `cols × rows` эмулятора) — статус-бар показывает его вместо мока. */
    var cols: Int by mutableStateOf(emulator.cols)
        private set

    var rows: Int by mutableStateOf(emulator.rows)
        private set

    var cursorRow: Int by mutableStateOf(0)
        private set

    var cursorCol: Int by mutableStateOf(0)
        private set

    /** Виден ли курсор (DEC ?25): TUI прячут его на время перерисовки. Рендер не рисует скрытый. */
    var cursorVisible: Boolean by mutableStateOf(true)
        private set

    /** Форма курсора (DECSCUSR): блок/подчёркивание/черта. Рендер выбирает геометрию по ней. Стартует с настройки. */
    var cursorShape: CursorShape by mutableStateOf(cursorShape)
        private set

    /** Должен ли курсор мигать (DECSCUSR steady/blink). Рендер гоняет таймер мигания по флагу. Стартует с настройки. */
    var cursorBlink: Boolean by mutableStateOf(cursorBlink)
        private set

    /** Текущее выделение мышью (или `null`, если ничего не выделено). Рендер подсвечивает его. */
    var selection: TerminalSelection? by mutableStateOf(null)
        private set

    /**
     * DECCKM-режим (application-cursor-keys), снятый с эмулятора: vim/less/htop включают его, и тогда
     * клавиши-стрелки клавишной панели должны слаться как SS3 (`ESC O A`), а не CSI. UI читает флаг
     * при кодировании стрелок ([app.skerry.ui.design.arrowSequence]).
     */
    var applicationCursorKeys: Boolean by mutableStateOf(false)
        private set

    /**
     * Application-keypad-режим (DECKPAM/DECKPNM), снятый с эмулятора: когда включён, numpad-клавиши
     * шлются как SS3 (`ESC O p`..`ESC O y` и т.п.), а не как цифры. UI читает флаг при кодировании клавиш.
     */
    var applicationKeypad: Boolean by mutableStateOf(false)
        private set

    /**
     * Режим репортинга мыши, снятый с эмулятора (DEC 1000/1002/1003 + X10). Когда не [MouseTracking.Off],
     * приложение само обрабатывает мышь: UI шлёт ему события вместо локального выделения (если не
     * зажат Shift, который форсит локальное выделение по xterm-конвенции).
     */
    var mouseTracking: MouseTracking by mutableStateOf(MouseTracking.Off)
        private set

    /** SGR-кодировка мыши (DEC 1006) — выбирает формат отчётов в [reportMouse]. */
    var mouseSgr: Boolean by mutableStateOf(false)
        private set

    /** SGR-Pixels (DEC 1016): координаты в пикселях вместо клеток — см. [reportMouse]. */
    var mousePixels: Boolean by mutableStateOf(false)
        private set

    /** Bracketed-paste (DEC 2004): когда включён, [paste] оборачивает вставку маркерами. */
    var bracketedPaste: Boolean by mutableStateOf(false)
        private set

    /** Focus reporting (DEC 1004): когда включён, [notifyFocus] шлёт ESC[I/ESC[O при смене фокуса. */
    var focusReporting: Boolean by mutableStateOf(false)
        private set

    /** Активен альтернативный буфер (полноэкранные TUI): без своего scrollback, колесо ≠ прокрутке. */
    var altScreen: Boolean by mutableStateOf(false)
        private set

    /** Заголовок окна из OSC 0/1/2 (пустой, пока приложение его не задало). UI кладёт его на вкладку. */
    var title: String by mutableStateOf("")
        private set

    /**
     * Переопределения палитры (OSC 4/104): index 0..255 → Rgb. Пусто, пока приложение их не задавало.
     * Рендер консультируется при разрешении [TermColor.Indexed] перед дефолтами темы.
     */
    var palette: Map<Int, TermColor.Rgb> by mutableStateOf(emptyMap())
        private set

    /**
     * Плоский текст экрана — для тестов и простых проверок (рендер использует [screen]).
     * Сетка всегда `rows` строк фиксированной ширины, поэтому хвостовые пробелы и пустые строки
     * обрезаются, чтобы текст читался как видимое содержимое.
     */
    val output: String
        get() = screen
            .joinToString("\n") { row -> buildString { row.forEach { append(it.text) } }.trimEnd() }
            .trimEnd('\n')

    val state: StateFlow<TerminalState> get() = session.state

    // Эмулятор однопоточный: feed и resize нельзя дёргать из разных корутин (гонка). Все
    // воздействия на него проходят командной очередью, которую разбирает единственный сборщик ниже,
    // — так вывод PTY и ресайз сериализованы относительно друг друга.
    private val commands = Channel<TerminalCommand>(Channel.UNLIMITED)

    // Очередь исходящих байтов в PTY (ввод, отчёты мыши, ответы DSR/DA). Единственный потребитель в
    // init сериализует запись, сохраняя порядок при отправке из разных корутин. UNLIMITED → trySend
    // никогда не блокирует и не теряет (fire-and-forget, как и было у send/sendBytes).
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    // Последний размер, отданный в PTY: дубликаты гасим, чтобы не спамить resize при перелэйауте.
    // @Volatile — resize() может зваться из разных корутин (LaunchedEffect/жесты), нужна видимость.
    @Volatile
    private var lastRequestedSize: PtySize? = null

    init {
        // Единственный разрешённый сборщик вывода перекладывает чанки в командную очередь.
        // По завершении вывода (EOF/закрытие сессии) закрываем очередь, иначе сборщик-владелец
        // ниже навсегда повиснет в `for (cmd in commands)`.
        scope.launch {
            try {
                session.output.collect { chunk -> commands.send(TerminalCommand.Feed(chunk)) }
            } finally {
                commands.close()
            }
        }
        // Единственный владелец эмулятора: feed и resize выполняются строго по очереди.
        scope.launch {
            for (cmd in commands) {
                when (cmd) {
                    is TerminalCommand.Feed -> emulator.feed(cmd.chunk)
                    is TerminalCommand.SetCursorDefault -> emulator.applyCursorDefault(cmd.shape, cmd.blink)
                    is TerminalCommand.SetMaxScrollback -> emulator.applyMaxScrollback(cmd.lines)
                    is TerminalCommand.Resize -> {
                        // PTY ресайзим ПЕРВЫМ, эмулятор — только при успехе: иначе сетка станет шире,
                        // чем знает приложение, и хвост строк зависнет нестёртым. Сбой PTY-ресайза НЕ
                        // должен убивать корутину-обработчик (иначе feed перестанет обрабатываться и
                        // терминал замёрзнет), поэтому ловим здесь.
                        try {
                            session.resize(cmd.size)
                            emulator.resize(cmd.size.cols, cmd.size.rows)
                        } catch (e: CancellationException) {
                            throw e // отмену скоупа не глушим — structured concurrency должна свалить обработчик
                        } catch (_: Exception) {
                            // только восстановимые сбои (например, обрыв PTY); Error пробрасываем
                        }
                    }
                }
                publishSnapshot()
            }
        }
        // Единственный потребитель исходящих байтов: гарантирует FIFO-порядок записи в PTY независимо
        // от того, из скольких корутин звали send/sendBytes (иначе порядок держался лишь на внутренней
        // синхронизации транспорта). Все отправки проходят через [outbound].
        scope.launch {
            for (bytes in outbound) session.send(bytes)
        }
    }

    /** Опубликовать снимок эмулятора в Compose-state (после feed/resize). */
    private fun publishSnapshot() {
        screen = emulator.lines // строки уже скопированы в неизменяемые внутри геттера
        cols = emulator.cols
        rows = emulator.rows
        cursorRow = emulator.cursorRow
        cursorCol = emulator.cursorCol
        cursorVisible = emulator.cursorVisible
        cursorShape = emulator.cursorShape
        cursorBlink = emulator.cursorBlink
        applicationCursorKeys = emulator.applicationCursorKeys
        applicationKeypad = emulator.applicationKeypad
        mouseTracking = emulator.mouseTracking
        mouseSgr = emulator.mouseSgr
        mousePixels = emulator.mousePixels
        bracketedPaste = emulator.bracketedPaste
        focusReporting = emulator.focusReporting
        altScreen = emulator.altScreen
        // Вход в полноэкранный TUI (vim/htop) гасит подсказку автодополнения — «строки» там нет.
        if (altScreen && suggestionTail != null) suggestionTail = null
        title = emulator.title
        palette = emulator.paletteSnapshot()
        snapshotVersion++
    }

    /** Начать выделение в позиции [pos] (нажатие мыши): якорь и фокус совпадают — пока пусто. */
    fun beginSelection(pos: TerminalPos) {
        selection = TerminalSelection(anchor = pos, focus = pos)
    }

    /** Протянуть выделение до [pos] (перетаскивание): двигаем фокус, якорь на месте. */
    fun extendSelection(pos: TerminalPos) {
        selection = selection?.copy(focus = pos)
    }

    /**
     * Выделить целое слово под [pos] — для long-press: непрерывный пробег непробельных (или
     * пробельных) ячеек на строке ([wordSelectionAt]). Пустой пробег выделения не ставит.
     */
    fun selectWordAt(pos: TerminalPos) {
        selection = wordSelectionAt(screen, pos).takeIf { !it.isEmpty }
    }

    /** Выделить целую строку под [pos] — для тройного клика мышью ([lineSelectionAt]). */
    fun selectLineAt(pos: TerminalPos) {
        selection = lineSelectionAt(screen, pos).takeIf { !it.isEmpty }
    }

    /**
     * Сдвинуть верхнюю-левую границу выделения в [pos] (перетаскивание start-маркера): держим
     * нижнюю-правую границу как якорь, новая позиция становится фокусом. No-op без выделения.
     */
    fun moveSelectionStart(pos: TerminalPos) {
        selection = selection?.let { TerminalSelection(anchor = it.end, focus = pos) }
    }

    /**
     * Сдвинуть нижнюю-правую границу выделения в [pos] (перетаскивание end-маркера): держим
     * верхнюю-левую границу как якорь, новая позиция становится фокусом. No-op без выделения.
     */
    fun moveSelectionEnd(pos: TerminalPos) {
        selection = selection?.let { TerminalSelection(anchor = it.start, focus = pos) }
    }

    /** Снять выделение (клик/новый ввод). */
    fun clearSelection() {
        selection = null
    }

    /** Текст текущего выделения для копирования или `null`, если выделять нечего. */
    fun selectedText(): String? = selection
        ?.takeIf { !it.isEmpty }
        ?.extract(screen)
        ?.takeIf { it.isNotEmpty() }

    /**
     * In-app PRIMARY-буфер: текст последнего выделения мышью. Нужен для среднего клика-вставки там,
     * где системный PRIMARY недоступен (Wayland: AWT `getSystemSelection()`==null) — тогда вставка
     * берёт его вместо CLIPBOARD, чтобы средний клик вставлял именно выделенное, а не устаревший буфер.
     */
    var primarySelection: String? = null
        private set

    /**
     * Зафиксировать текущее выделение как PRIMARY (вызывается по завершении выделения мышью). Возвращает
     * сохранённый текст или `null`, если выделять нечего (буфер тогда не трогаем — прежний PRIMARY живёт).
     */
    fun capturePrimarySelection(): String? {
        val text = selectedText() ?: return null
        primarySelection = text
        return text
    }

    // --- Автодополнение (Phase 3) ---
    // Движок отслеживает набираемую пользователем строку и предлагает продолжение из истории команд
    // этой сессии + типовых команд. Живёт на сессию (учится на её же командах). Подсказки работают
    // только в обычном (не alt-screen) режиме: в полноэкранных TUI (vim/htop) «строки» нет.
    private val autocomplete = AutocompleteEngine(
        CommandHistory().apply { if (initialHistory.isNotEmpty()) preload(initialHistory) },
    )

    /** «Хвост» текущей подсказки автодополнения (серым после набранного) или `null`. Читает UI. */
    var suggestionTail: String? by mutableStateOf(null)
        private set

    /**
     * Ввод пользователя с клавиатуры/IME: скармливает движку автодополнения (для отслеживания строки
     * и истории) и отправляет в PTY. Отдельно от [send]/[sendBytes], которыми идут отчёты мыши/фокуса,
     * paste и вывод сниппетов — их движок видеть не должен, иначе строка исказится.
     */
    fun typeInput(text: String) {
        // Сервер не эхоит ввод (ввод пароля / line-mode, сигнал от транспорта) — НЕ отслеживаем строку
        // и НЕ пишем её в историю: секрет не должен оседать в памяти и всплывать подсказкой. Для SSH
        // эхо-статус недоступен (всегда false) — остаточный риск in-session паролей осознан.
        if (session.echoSuppressed) {
            autocomplete.reset()
            if (suggestionTail != null) suggestionTail = null
            send(text)
            return
        }
        val committed = autocomplete.onUserInput(text.encodeToByteArray())
        refreshSuggestion()
        send(text)
        // Команда подтверждена Enter (и была с эхом) — персистим снимок истории для этого хоста.
        if (committed != null) onHistoryChanged?.invoke(autocomplete.commandHistory.commands)
    }

    /**
     * Принять текущую подсказку автодополнения: дослать её «хвост» в PTY (shell сам его отобразит эхом).
     * Возвращает `true`, если было что принять (UI тогда гасит своё событие, напр. Tab), иначе `false`.
     */
    fun acceptSuggestion(): Boolean {
        if (altScreen) return false
        val tail = autocomplete.acceptSuggestion() ?: return false
        refreshSuggestion()
        sendBytes(tail)
        return true
    }

    /**
     * Переключить «призрак» подсказки на следующую альтернативу (Shift+Tab). В alt-screen подсказок
     * нет — no-op. Строку в PTY не трогает: меняется лишь предлагаемый хвост до его принятия.
     */
    fun cycleSuggestion() {
        if (altScreen) return
        autocomplete.cycleSuggestion()
        suggestionTail = autocomplete.suggestionTail()
    }

    /** Совпадения истории команд для reverse-search (Ctrl-R), от новой к старой; пустой query — пусто. */
    fun historyMatches(query: String): List<String> = autocomplete.commandHistory.search(query)

    // --- Reverse-search истории (Ctrl-R): состояние оверлея живёт здесь, чтобы им одинаково управляли
    // и desktop-клавиши, и мобильная панель/IME, а рендер-оверлей читал единый источник. ---

    /** Текущий запрос reverse-search или `null`, если оверлей закрыт. Читает UI. */
    var reverseSearchQuery: String? by mutableStateOf(null)
        private set

    /** Индекс выбранного совпадения в [reverseSearchResults]. */
    var reverseSearchIndex: Int by mutableStateOf(0)
        private set

    /** Совпадения текущего запроса (от новой к старой) или пусто, если оверлей закрыт. */
    val reverseSearchResults: List<String>
        get() = reverseSearchQuery?.let { autocomplete.commandHistory.search(it) } ?: emptyList()

    /** Выбранное совпадение (под [reverseSearchIndex]) или `null`. */
    val reverseSearchSelection: String?
        get() {
            val r = reverseSearchResults
            return if (r.isEmpty()) null else r[reverseSearchIndex.mod(r.size)]
        }

    /** Открыть reverse-search (пустой запрос). В alt-screen — no-op (истории строк там нет). */
    fun openReverseSearch() {
        if (altScreen) return
        reverseSearchQuery = ""
        reverseSearchIndex = 0
    }

    /** Закрыть оверлей reverse-search без вставки. */
    fun closeReverseSearch() {
        reverseSearchQuery = null
        reverseSearchIndex = 0
    }

    /** Дописать [text] к запросу reverse-search (сброс выбора на первое совпадение). */
    fun reverseSearchAppend(text: String) {
        val q = reverseSearchQuery ?: return
        reverseSearchQuery = q + text
        reverseSearchIndex = 0
    }

    /** Удалить последний символ запроса reverse-search. */
    fun reverseSearchBackspace() {
        val q = reverseSearchQuery ?: return
        reverseSearchQuery = q.dropLast(1)
        reverseSearchIndex = 0
    }

    /** К следующему (более старому) совпадению. */
    fun reverseSearchNext() {
        val n = reverseSearchResults.size
        if (n > 0) reverseSearchIndex = (reverseSearchIndex + 1) % n
    }

    /** К предыдущему (более новому) совпадению. */
    fun reverseSearchPrev() {
        val n = reverseSearchResults.size
        if (n > 0) reverseSearchIndex = (reverseSearchIndex - 1 + n) % n
    }

    /** Принять выбранное совпадение (вставить в строку через [applyHistoryCommand]) и закрыть оверлей. */
    fun reverseSearchAccept() {
        reverseSearchSelection?.let { applyHistoryCommand(it) }
        closeReverseSearch()
    }

    /**
     * Удалить [command] из истории автодополнения (ручная чистка опечаток/ненужных команд) и обновить
     * персист. Как в bash/zsh, история пишет всё подряд — это способ убрать конкретную запись из
     * подсказок. Индекс reverse-search подравнивается, чтобы выбор остался в границах.
     */
    fun forgetHistoryCommand(command: String) {
        if (!autocomplete.forget(command)) return
        val n = reverseSearchResults.size
        if (n == 0) reverseSearchIndex = 0 else if (reverseSearchIndex >= n) reverseSearchIndex = n - 1
        onHistoryChanged?.invoke(autocomplete.commandHistory.commands)
        refreshSuggestion()
    }

    /** Удалить выбранное в reverse-search совпадение из истории; оверлей остаётся открыт. */
    fun reverseSearchDeleteSelected() {
        reverseSearchSelection?.let { forgetHistoryCommand(it) }
    }

    /**
     * Вставить выбранную из истории команду: очистить текущую строку shell (Ctrl-U) и «набрать» её,
     * чтобы пользователь мог отредактировать/запустить. Идёт через [typeInput] — движок снова видит
     * строку, а гейт echoSuppressed сохраняется (в редком режиме без эха просто уйдёт как ввод).
     */
    fun applyHistoryCommand(command: String) {
        sendBytes(byteArrayOf(0x15)) // Ctrl-U — стереть текущую строку ввода (readline kill-line)
        autocomplete.reset()
        typeInput(command)
    }

    private fun refreshSuggestion() {
        suggestionTail = if (altScreen) null else autocomplete.suggestionTail()
    }

    /** Отправить введённый текст в PTY (fire-and-forget через очередь [outbound], FIFO-порядок). */
    fun send(text: String) {
        outbound.trySend(text.encodeToByteArray())
    }

    /**
     * Отправить сырые байты в PTY (fire-and-forget). Нужен для отчётов мыши: в legacy-кодировке
     * байты могут выходить за 0x7f и не должны прогоняться через UTF-8, как делает [send].
     */
    fun sendBytes(bytes: ByteArray) {
        outbound.trySend(bytes)
    }

    /**
     * Закодировать событие мыши под текущий режим/кодировку эмулятора и отправить в PTY. Возвращает
     * `true`, если отчёт был отправлен (событие репортится в активном режиме), иначе `false` —
     * вызывающий тогда может обработать событие локально. No-op без репортинга мыши.
     */
    fun reportMouse(
        button: MouseButton,
        type: MouseEventType,
        pos: TerminalPos,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
        pixelX: Int = 0,
        pixelY: Int = 0,
    ): Boolean {
        val bytes = encodeMouseReport(
            mouseTracking, mouseSgr, button, type, pos.col, pos.row, shift, alt, ctrl,
            pixels = mousePixels, pixelX = pixelX, pixelY = pixelY,
        ) ?: return false
        sendBytes(bytes)
        return true
    }

    /**
     * Уведомить приложение о смене фокуса окна терминала: при включённом focus-reporting (DEC 1004)
     * шлёт ESC[I (фокус) или ESC[O (потеря). No-op, если приложение режим не запрашивало.
     */
    fun notifyFocus(focused: Boolean) {
        if (focusReporting) send(focusReportSequence(focused))
    }

    /** Вставить текст из буфера: при включённом bracketed-paste оборачивает маркерами (DEC 2004). */
    fun paste(text: String) {
        if (text.isEmpty()) return
        send(bracketedPasteWrap(text, bracketedPaste))
    }

    /**
     * Сообщить новый размер сетки. Применяется и к эмулятору, и к PTY через ту же командную очередь,
     * что и [feed][TerminalEmulator.feed] (без гонки). Повтор того же размера игнорируется.
     */
    fun resize(size: PtySize) {
        if (size.cols == lastRequestedSize?.cols && size.rows == lastRequestedSize?.rows) return
        lastRequestedSize = size
        commands.trySend(TerminalCommand.Resize(size))
    }

    /**
     * Сменить стиль курсора по умолчанию у уже открытой сессии (настройка изменилась на лету). Идёт
     * через ту же командную очередь, что feed/resize — без гонки с однопоточным эмулятором; снимок
     * публикуется автоматически после обработки, так что курсор перерисуется сразу.
     */
    fun applyCursorStyle(shape: CursorShape, blink: Boolean) {
        commands.trySend(TerminalCommand.SetCursorDefault(shape, blink))
    }

    /**
     * Сменить глубину scrollback у уже открытой сессии (настройка «Буфер прокрутки» изменилась на
     * лету). Идёт через ту же командную очередь — при уменьшении лишние старые строки сразу
     * обрезаются, снимок публикуется автоматически.
     */
    fun applyScrollback(lines: Int) {
        commands.trySend(TerminalCommand.SetMaxScrollback(lines))
    }
}

/** Команда единственному владельцу эмулятора — порядок feed/resize сохраняется очередью. */
private sealed interface TerminalCommand {
    /** Сырой чанк вывода PTY на скармливание парсеру. */
    class Feed(val chunk: ByteArray) : TerminalCommand

    /** Новый размер сетки: применяется к эмулятору и пробрасывается в PTY. */
    class Resize(val size: PtySize) : TerminalCommand

    /** Новый пользовательский дефолт курсора (настройка сменилась при открытой сессии). */
    class SetCursorDefault(val shape: CursorShape, val blink: Boolean) : TerminalCommand

    /** Новая глубина scrollback (настройка «Буфер прокрутки» сменилась при открытой сессии). */
    class SetMaxScrollback(val lines: Int) : TerminalCommand
}
