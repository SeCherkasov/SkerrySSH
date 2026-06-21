package app.skerry.ui.forward

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Направление проброса: локальный (`-L`), обратный (`-R`) или динамический SOCKS (`-D`). */
enum class ForwardDirection { Local, Remote, Dynamic }

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

    /** На паузе ли проброс (тумблер ACTIVE снят, но порт держится). Меняется через [PortForwardController.pause]/[resume]. */
    var paused: Boolean by mutableStateOf(false)
        internal set

    /** Суммарно байт, ушедших к серверу — снимок последнего опроса телеметрии. */
    var bytesUp: Long by mutableStateOf(0)
        internal set

    /** Суммарно байт, пришедших от сервера — снимок последнего опроса телеметрии. */
    var bytesDown: Long by mutableStateOf(0)
        internal set

    /** Текущая скорость отдачи (байт/с) по дельте между опросами. */
    var upRate: Long by mutableStateOf(0)
        internal set

    /** Текущая скорость приёма (байт/с) по дельте между опросами. */
    var downRate: Long by mutableStateOf(0)
        internal set

    internal var handle: PortForward? = null

    // Предыдущий снимок счётчиков — для расчёта скорости в опросе (наружу не отдаётся).
    internal var prevUp: Long = 0
    internal var prevDown: Long = 0
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
    private val pollIntervalMillis: Long = 1000,
) {
    var forwards: List<ForwardEntry> by mutableStateOf(emptyList())
        private set

    private var nextId = 0L

    init {
        // Опрос телеметрии живёт на scope сессии: пока сессия жива, по каждому активному пробросу
        // снимаем счётчики байт и считаем скорость (байт/с) по дельте за интервал. Отмену scope
        // (disconnect) цикл не глотает — выходит по isActive.
        scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                pollTelemetry()
            }
        }
    }

    internal fun pollTelemetry() {
        forwards.forEach { entry ->
            val handle = entry.handle ?: return@forEach
            val up = handle.bytesUp
            val down = handle.bytesDown
            entry.upRate = ((up - entry.prevUp) * 1000 / pollIntervalMillis).coerceAtLeast(0)
            entry.downRate = ((down - entry.prevDown) * 1000 / pollIntervalMillis).coerceAtLeast(0)
            entry.prevUp = up
            entry.prevDown = down
            entry.bytesUp = up
            entry.bytesDown = down
        }
    }

    /** Поднять локальный проброс (`-L`). [bindPort] `0` — порт выберет ОС. */
    fun addLocal(bindPort: Int, destHost: String, destPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Local, bindHost, bindPort, destHost, destPort)

    /** Поднять обратный проброс (`-R`). [bindPort] `0` — порт назначит сервер. */
    fun addRemote(bindPort: Int, destHost: String, destPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Remote, bindHost, bindPort, destHost, destPort)

    /**
     * Поднять динамический проброс (`-D`, SOCKS5-прокси). Адрес назначения динамический, поэтому
     * параметров назначения нет; [bindPort] `0` — порт выберет ОС.
     */
    fun addDynamic(bindPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Dynamic, bindHost, bindPort, destHost = "", destPort = 0)

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
                    ForwardDirection.Dynamic ->
                        connection.forwardDynamic(DynamicForwardSpec(bindHost, bindPort))
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

    /**
     * Приостановить проброс [entry]: слушатель держит порт, но новые соединения не туннелируются.
     * Флаг [ForwardEntry.paused] обновляется сразу (для отзывчивого тумблера), сама пауза слушателя —
     * на [scope]. Действует только на активный проброс.
     */
    fun pause(entry: ForwardEntry) {
        if (entry.status !is ForwardStatus.Active) return
        entry.paused = true
        scope.launch { runCatching { entry.handle?.pause() } }
    }

    /** Возобновить ранее приостановленный проброс [entry]. */
    fun resume(entry: ForwardEntry) {
        if (entry.status !is ForwardStatus.Active) return
        entry.paused = false
        scope.launch { runCatching { entry.handle?.resume() } }
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
