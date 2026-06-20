package app.skerry.ui.forward

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Направление проброса: локальный (`-L`) или обратный (`-R`). */
enum class ForwardDirection { Local, Remote }

/** Состояние одного проброса в списке. */
sealed interface ForwardStatus {
    /** Слушатель поднимается. */
    data object Starting : ForwardStatus

    /** Проброс активен; [boundPort] — фактический порт слушателя (для запроса `0` — назначенный). */
    data class Active(val boundPort: Int) : ForwardStatus

    /** Поднять не удалось; [message] для показа пользователю. */
    data class Failed(val message: String) : ForwardStatus
}

/**
 * Одна строка списка пробросов. Параметры неизменны; [status] — наблюдаемый Compose-стейт,
 * меняется контроллером. [handle] держит живой [PortForward] для последующего закрытия (наружу не
 * отдаётся).
 */
@Stable
class ForwardEntry internal constructor(
    val id: Long,
    val direction: ForwardDirection,
    val bindHost: String,
    val requestedPort: Int,
    val destHost: String,
    val destPort: Int,
) {
    var status: ForwardStatus by mutableStateOf(ForwardStatus.Starting)
        internal set

    internal var handle: PortForward? = null
}

/**
 * Контроллер списка пробросов портов поверх живого [SshConnection]. По образцу
 * [app.skerry.ui.sftp.SftpController]: операции `SshConnection` — `suspend`, поэтому контроллер
 * держит [scope] и поднимает/снимает пробросы через [launch].
 *
 * Каждый проброс живёт своей строкой [ForwardEntry] и не блокирует другие — несколько пробросов
 * могут стартовать параллельно. Ошибка поднятия переводит строку в [ForwardStatus.Failed], не роняя
 * контроллер и не трогая остальные. Владение [SshConnection] — снаружи; контроллер закрывает только
 * сами пробросы ([remove]/[closeAll]), но не соединение.
 */
@Stable
class PortForwardController(
    private val connection: SshConnection,
    private val scope: CoroutineScope,
) {
    var forwards: List<ForwardEntry> by mutableStateOf(emptyList())
        private set

    private var nextId = 0L

    /** Поднять локальный проброс (`-L`). [bindPort] `0` — порт выберет ОС. */
    fun addLocal(bindPort: Int, destHost: String, destPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Local, bindHost, bindPort, destHost, destPort)

    /** Поднять обратный проброс (`-R`). [bindPort] `0` — порт назначит сервер. */
    fun addRemote(bindPort: Int, destHost: String, destPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Remote, bindHost, bindPort, destHost, destPort)

    private fun add(
        direction: ForwardDirection,
        bindHost: String,
        bindPort: Int,
        destHost: String,
        destPort: Int,
    ) {
        val entry = ForwardEntry(nextId++, direction, bindHost, bindPort, destHost, destPort)
        forwards = forwards + entry
        scope.launch {
            try {
                val forward = when (direction) {
                    ForwardDirection.Local ->
                        connection.forwardLocal(LocalForwardSpec(bindHost, bindPort, destHost, destPort))
                    ForwardDirection.Remote ->
                        connection.forwardRemote(RemoteForwardSpec(bindHost, bindPort, destHost, destPort))
                }
                entry.handle = forward
                entry.status = ForwardStatus.Active(forward.boundPort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ожидаемая ошибка — PortForwardException; ловим шире, чтобы нежданное исключение из
                // sshj не уронило общий scope сессии (его делит SFTP), а осело в строке как Failed.
                entry.status = ForwardStatus.Failed(e.message ?: "Не удалось поднять проброс")
            }
        }
    }

    /** Снять проброс [entry]: убрать из списка и закрыть слушатель (если уже поднялся). */
    fun remove(entry: ForwardEntry) {
        forwards = forwards - entry
        scope.launch { runCatching { entry.handle?.close() } }
    }

    /** Снять все пробросы (при закрытии панели/сессии). Соединение остаётся открытым. */
    fun closeAll() {
        val current = forwards
        forwards = emptyList()
        scope.launch { current.forEach { runCatching { it.handle?.close() } } }
    }
}
