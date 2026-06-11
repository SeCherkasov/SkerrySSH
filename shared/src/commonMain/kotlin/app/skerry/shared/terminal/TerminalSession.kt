package app.skerry.shared.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Жизненный цикл сессии. Closed — канал закрыт (EOF, обрыв или [TerminalSession.close]). */
enum class TerminalState { Open, Closed }

/**
 * Интерактивная терминальная сессия поверх [ShellChannel].
 *
 * Снимает с UI два ограничения сырого канала: единственного разрешённого сборщика
 * [ShellChannel.output] берёт на себя сессия и переизлучает вывод как горячий [output]
 * на произвольное число подписчиков (UI пересоздаёт подписку при перерисовке).
 * Scrollback-историю сессия не хранит — это ответственность терминального эмулятора в UI.
 */
interface TerminalSession {
    val state: StateFlow<TerminalState>

    /**
     * Горячий поток вывода PTY. Подписчики получают байты с момента подписки;
     * накопленной истории нет. Завершения не несёт — об окончании сессии говорит [state].
     */
    val output: Flow<ByteArray>

    /** @throws app.skerry.shared.ssh.SshConnectionException канал закрыт или обрыв транспорта */
    suspend fun send(data: ByteArray)

    suspend fun resize(size: PtySize)

    suspend fun close()
}

/**
 * Реализация поверх открытого [channel]. Сбор вывода живёт в [scope]: его завершение
 * (EOF, отмена, исключение) переводит сессию в [TerminalState.Closed]. Отмена [scope]
 * извне останавливает сессию вместе со сбором.
 */
class ShellTerminalSession(
    private val channel: ShellChannel,
    scope: CoroutineScope,
) : TerminalSession {

    private val _state = MutableStateFlow(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val output: Flow<ByteArray> = _output.asSharedFlow()

    init {
        scope.launch {
            try {
                channel.output.collect { _output.emit(it) }
            } catch (e: CancellationException) {
                // Отмена scope должна корректно сворачивать сессию — пробрасываем.
                throw e
            } catch (_: Exception) {
                // Обрыв транспорта завершает сессию (см. finally), но не должен ронять
                // scope, в котором живёт сбор вывода.
            } finally {
                _state.value = TerminalState.Closed
            }
        }
    }

    override suspend fun send(data: ByteArray) = channel.write(data)

    override suspend fun resize(size: PtySize) = channel.resize(size)

    override suspend fun close() = channel.close()
}
