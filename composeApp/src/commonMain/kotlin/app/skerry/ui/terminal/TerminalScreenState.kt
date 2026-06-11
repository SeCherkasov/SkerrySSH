package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Состояние терминального экрана поверх [TerminalSession]. Подписывается на вывод
 * сессии в [scope], накапливает сырые байты и декодирует их в [output] для отрисовки;
 * декодирование идёт по всему буферу, поэтому UTF-8 на границе чанков не бьётся.
 * Ввод и ресайз проксируются в сессию.
 *
 * Это минимальный экран: ANSI/VT-управляющие последовательности пока показываются
 * как есть (полноценный эмулятор — отдельный шаг). Сырой буфер ограничен [maxBufferBytes]:
 * при переполнении отбрасываются старейшие байты (усечение scrollback), поэтому память
 * не растёт без предела, а копирование/декод на каждый чанк ограничены этим потолком.
 * Декод всего буфера сохраняет корректность UTF-8 на границе чанков; инкрементальный
 * (построчный) декод придёт вместе с VT-эмулятором.
 */
@Stable
class TerminalScreenState(
    private val session: TerminalSession,
    private val scope: CoroutineScope,
    private val maxBufferBytes: Int = DEFAULT_MAX_BUFFER_BYTES,
) {
    init {
        require(maxBufferBytes > 0) { "maxBufferBytes должен быть положительным" }
    }

    private var raw = ByteArray(0)

    /** Накопленный декодированный вывод PTY; Compose-state — перерисовка на изменении. */
    var output by mutableStateOf("")
        private set

    val state: StateFlow<TerminalState> get() = session.state

    init {
        scope.launch {
            session.output.collect { chunk ->
                raw = appendBounded(raw, chunk)
                output = raw.decodeToString()
            }
        }
    }

    /**
     * Добавляет [chunk] к [current], удерживая итог в пределах [maxBufferBytes]: при
     * переполнении сохраняется только хвост (новейшие байты). Усечение по байтам может
     * оставить «обрубленный» многобайтовый символ в начале — он отрисуется как U+FFFD;
     * для отброшенного scrollback это приемлемо.
     */
    private fun appendBounded(current: ByteArray, chunk: ByteArray): ByteArray {
        if (chunk.size >= maxBufferBytes) {
            return chunk.copyOfRange(chunk.size - maxBufferBytes, chunk.size)
        }
        val total = current.size + chunk.size
        if (total <= maxBufferBytes) {
            return current + chunk
        }
        val result = ByteArray(maxBufferBytes)
        val keptFromCurrent = maxBufferBytes - chunk.size
        current.copyInto(result, destinationOffset = 0, startIndex = current.size - keptFromCurrent)
        chunk.copyInto(result, destinationOffset = keptFromCurrent)
        return result
    }

    private companion object {
        /** Потолок scrollback по умолчанию (1 МиБ сырых байтов). */
        const val DEFAULT_MAX_BUFFER_BYTES = 1 shl 20
    }

    /** Отправить введённый текст в PTY (fire-and-forget в [scope]). */
    fun send(text: String) {
        scope.launch { session.send(text.encodeToByteArray()) }
    }

    fun resize(size: PtySize) {
        scope.launch { session.resize(size) }
    }
}
