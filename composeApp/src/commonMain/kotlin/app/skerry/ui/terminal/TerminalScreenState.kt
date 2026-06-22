package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.CursorShape
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
) {
    // respond: ответы терминала (DSR/DA) уходят обратно в PTY — иначе приложения, опрашивающие
    // курсор/атрибуты, подвисают в ожидании. ВАЖНО: колбэк зовётся синхронно из emulator.feed()
    // (внутри корутины-владельца), поэтому он обязан лишь писать в PTY (send → session.send) и НЕ
    // должен прямо/косвенно заводить новый emulator.feed/resize — иначе ломается однопоточный
    // контракт эмулятора. session.send в PTY-сток, обратно в commands не возвращается.
    // Запросы OSC 52 на запись в системный буфер. extraBufferCapacity, чтобы tryEmit с владельца-корутины
    // не терялся при отсутствии подписчика в момент эмита; DROP_OLDEST — при всплеске буфер получает
    // последнюю запись (last-writer-wins, верная семантика для clipboard), а не застревает на старой.
    private val _clipboardCopies = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Текст, который приложение просит положить в системный буфер (OSC 52). UI собирает и кладёт. */
    val clipboardCopies: SharedFlow<String> = _clipboardCopies

    private val emulator = TerminalEmulator(
        respond = { reply -> send(reply) },
        // OSC 52-запись: эмулятор зовёт это синхронно из feed() на корутине-владельце, поэтому НЕ трогаем
        // системный буфер здесь — публикуем во flow, а композбл кладёт в буфер на UI-потоке.
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

    /** Форма курсора (DECSCUSR): блок/подчёркивание/черта. Рендер выбирает геометрию по ней. */
    var cursorShape: CursorShape by mutableStateOf(CursorShape.Block)
        private set

    /** Должен ли курсор мигать (DECSCUSR steady/blink). Рендер гоняет таймер мигания по флагу. */
    var cursorBlink: Boolean by mutableStateOf(true)
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
}

/** Команда единственному владельцу эмулятора — порядок feed/resize сохраняется очередью. */
private sealed interface TerminalCommand {
    /** Сырой чанк вывода PTY на скармливание парсеру. */
    class Feed(val chunk: ByteArray) : TerminalCommand

    /** Новый размер сетки: применяется к эмулятору и пробрасывается в PTY. */
    class Resize(val size: PtySize) : TerminalCommand
}
