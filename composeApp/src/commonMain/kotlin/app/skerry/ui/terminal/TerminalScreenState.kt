package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalEmulator
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.wordSelectionAt
import kotlinx.coroutines.CoroutineScope
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
    private val emulator = TerminalEmulator()

    /** Снимок экрана (строки сверху вниз) для отрисовки. */
    var screen: List<List<TermCell>> by mutableStateOf(emptyList())
        private set

    var cursorRow: Int by mutableStateOf(0)
        private set

    var cursorCol: Int by mutableStateOf(0)
        private set

    /** Текущее выделение мышью (или `null`, если ничего не выделено). Рендер подсвечивает его. */
    var selection: TerminalSelection? by mutableStateOf(null)
        private set

    /** Плоский текст экрана — для тестов и простых проверок (рендер использует [screen]). */
    val output: String
        get() = screen.joinToString("\n") { row -> buildString { row.forEach { append(it.char) } } }

    val state: StateFlow<TerminalState> get() = session.state

    init {
        scope.launch {
            session.output.collect { chunk ->
                emulator.feed(chunk)
                screen = emulator.lines.map { it.toList() }
                cursorRow = emulator.cursorRow
                cursorCol = emulator.cursorCol
            }
        }
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

    /** Отправить введённый текст в PTY (fire-and-forget в [scope]). */
    fun send(text: String) {
        scope.launch { session.send(text.encodeToByteArray()) }
    }

    fun resize(size: PtySize) {
        scope.launch { session.resize(size) }
    }
}
