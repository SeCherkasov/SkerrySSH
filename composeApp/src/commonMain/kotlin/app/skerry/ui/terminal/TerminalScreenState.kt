package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalEmulator
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
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

    /** Отправить введённый текст в PTY (fire-and-forget в [scope]). */
    fun send(text: String) {
        scope.launch { session.send(text.encodeToByteArray()) }
    }

    fun resize(size: PtySize) {
        scope.launch { session.resize(size) }
    }
}
