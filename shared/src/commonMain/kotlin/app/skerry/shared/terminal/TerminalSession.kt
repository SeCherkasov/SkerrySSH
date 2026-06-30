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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Жизненный цикл сессии. */
sealed interface TerminalState {
    /** Канал открыт, сессия живая. */
    data object Open : TerminalState

    /**
     * Канал закрыт. [cleanExit] = true, если сервер штатно завершил shell (EOF — например, по
     * команде `exit`): сессию закрываем без авто-реконнекта. false — канал оборвался ошибкой
     * транспорта либо его закрыл наш [TerminalSession.close]; для обрыва вызывающий запускает
     * авто-реконнект.
     */
    data class Closed(val cleanExit: Boolean = false) : TerminalState
}

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

    /**
     * Подавлен ли сейчас эхо-ответ сервера (ввод пароля / line-mode) — см. [ShellChannel.echoSuppressed].
     * По нему UI не пишет набранное в историю автодополнения. По умолчанию `false` (фейки/тесты).
     */
    val echoSuppressed: Boolean get() = false

    /** @throws app.skerry.shared.ssh.SshConnectionException канал закрыт или обрыв транспорта */
    suspend fun send(data: ByteArray)

    suspend fun resize(size: PtySize)

    suspend fun close()
}

/**
 * Реализация поверх открытого [channel]. Сбор вывода живёт в [scope]: его завершение
 * (EOF, отмена, исключение) переводит сессию в [TerminalState.Closed]. Штатный EOF канала
 * (shell сделал `exit`) даёт `cleanExit=true` — см. [ShellChannel.endedWithEof]. Отмена [scope]
 * извне останавливает сессию вместе со сбором.
 */
class ShellTerminalSession(
    private val channel: ShellChannel,
    scope: CoroutineScope,
) : TerminalSession {

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val output: Flow<ByteArray> = _output.asSharedFlow()

    // Прокидываем эхо-статус канала (Telnet сообщает ввод пароля / line-mode; SSH всегда false).
    override val echoSuppressed: Boolean get() = channel.echoSuppressed

    init {
        scope.launch {
            // КРИТИЧНО: сбор канала стартуем ТОЛЬКО после появления первого подписчика. Иначе
            // `init` начинает читать канал сразу при создании сессии и эмитит стартовый вывод
            // (баннер шелла/первый prompt) в [_output] — а у него replay=0, и без подписчиков
            // эмит ОТБРАСЫВАЕТСЯ. UI-коллектор подписывается на миллисекунды позже (channel.read
            // успевает вернуть баннер раньше), и первый экран остаётся пустым. Дождавшись
            // подписчика, читаем канал без потерь — ни один байт не проходит мимо.
            _output.subscriptionCount.first { it > 0 }
            try {
                channel.output.collect { _output.emit(it) }
            } catch (e: CancellationException) {
                // Отмена scope должна корректно сворачивать сессию — пробрасываем.
                throw e
            } catch (_: Exception) {
                // Обрыв транспорта завершает сессию (см. finally), но не должен ронять
                // scope, в котором живёт сбор вывода.
            } finally {
                // Штатный EOF канала (сервер закрыл shell сам — `exit`) даёт cleanExit=true: вызывающий
                // не реконнектит. Обрыв транспорта/отмена оставляют endedWithEof=false → cleanExit=false.
                _state.value = TerminalState.Closed(cleanExit = channel.endedWithEof)
            }
        }
    }

    override suspend fun send(data: ByteArray) = channel.write(data)

    override suspend fun resize(size: PtySize) = channel.resize(size)

    override suspend fun close() {
        // Состояние закрываем явно: сбор канала мог ещё не стартовать (нет подписчика),
        // и тогда [finally] сборщика не отработал бы, а сессия уже закрыта.
        channel.close()
        // Наше закрытие — не штатный выход shell: cleanExit=false (вызывающий уже инициатор закрытия).
        // Но не затираем уже выставленный сбором [Closed]: если сервер успел прислать штатный EOF
        // (cleanExit=true), это значение должно дойти до наблюдателя обрыва. Переходим только из Open.
        _state.update { current ->
            if (current == TerminalState.Open) TerminalState.Closed(cleanExit = false) else current
        }
    }
}
