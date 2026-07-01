package app.skerry.ui.tunnel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.TunnelStore
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_err_auth_failed
import app.skerry.ui.generated.resources.ptail_err_connection_failed
import app.skerry.ui.generated.resources.ptail_err_forward_failed
import app.skerry.ui.generated.resources.ptail_err_host_not_trusted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Редактируемые поля туннеля без [Tunnel.id]: форма создания/правки оперирует черновиком,
 * а идентичность присваивает [TunnelManager]. [id] == null — создаётся новый туннель.
 */
data class TunnelDraft(
    val id: String? = null,
    val label: String,
    val hostId: String,
    val direction: TunnelDirection,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
)

/** Резолв сохранённого туннеля к параметрам подключения: хост найден и секрет доступен — либо нет. */
sealed interface TunnelResolution {
    /** Готов к подключению: адрес хоста и развёрнутый из vault способ аутентификации. */
    data class Ready(val target: SshTarget, val auth: SshAuth) : TunnelResolution

    /**
     * Подключиться нельзя (хост удалён, секрет не привязан и т.п.). [reason] показывается в строке
     * как есть — реализация [resolve] обязана давать generic-текст без технических деталей (имён
     * файлов, id записей, системных сообщений): он минует [TunnelManager.friendlyError].
     */
    data class Unavailable(val reason: String) : TunnelResolution
}

/** Рантайм-состояние сохранённого туннеля (конфиг живёт в [Tunnel], это — статус включения). */
sealed interface TunnelStatus {
    /** Выключен: соединения нет, проброс не поднят. */
    data object Inactive : TunnelStatus

    /** Поднимается: открываем соединение и слушатель. */
    data object Connecting : TunnelStatus

    /** Активен; [boundPort] — фактический порт слушателя (для запроса `0` — назначенный). */
    data class Active(val boundPort: Int) : TunnelStatus

    /** Поднять не удалось; [message] для показа пользователю (без сырых деталей транспорта). */
    data class Failed(val message: String) : TunnelStatus
}

/**
 * Одна строка списка туннелей: сохранённый [tunnel] (конфиг, обновляется через [TunnelManager.save])
 * плюс наблюдаемое рантайм-состояние. [handle]/[connection] держат живой проброс и его собственное
 * SSH-соединение для последующего закрытия (наружу не отдаются — у каждого туннеля своё соединение,
 * как в популярных SSH-клиентах).
 */
@Stable
class TunnelEntry internal constructor(tunnel: Tunnel) {
    var tunnel: Tunnel by mutableStateOf(tunnel)
        internal set

    val id: String get() = tunnel.id

    var status: TunnelStatus by mutableStateOf(TunnelStatus.Inactive)
        internal set

    var bytesUp: Long by mutableStateOf(0)
        internal set
    var bytesDown: Long by mutableStateOf(0)
        internal set
    var upRate: Long by mutableStateOf(0)
        internal set
    var downRate: Long by mutableStateOf(0)
        internal set

    internal var handle: PortForward? = null
    internal var connection: SshConnection? = null

    // Корутина подъёма (пока статус Connecting): [TunnelManager.deactivate] отменяет её, чтобы
    // соединение, открывающееся прямо сейчас, не осело орфаном после выключения.
    internal var connectingJob: Job? = null

    internal var prevUp: Long = 0
    internal var prevDown: Long = 0

    internal fun resetCounters() {
        bytesUp = 0; bytesDown = 0; upRate = 0; downRate = 0; prevUp = 0; prevDown = 0
    }
}

/**
 * Менеджер сохранённых туннелей (привычная модель SSH-клиентов): туннель — самостоятельный объект в [TunnelStore],
 * а не часть открытой терминальной сессии. Включение ([activate]) само открывает SSH-соединение к
 * привязанному хосту через [transport] (в проде — транспорт с `ProbeHostKeyVerifier`: только уже
 * доверенные хосты) и поднимает проброс; [deactivate] закрывает проброс и его соединение. Резолв
 * хоста и секрета вынесен в [resolve], чтобы менеджер не зависел от менеджера хостов/vault напрямую
 * и тестировался без них.
 *
 * Каждый туннель живёт своей строкой [TunnelEntry] и не блокирует другие. Ошибка подъёма переводит
 * строку в [TunnelStatus.Failed], не роняя менеджер.
 */
@Stable
class TunnelManager(
    private val store: TunnelStore,
    private val transport: SshTransport,
    private val resolve: (Tunnel) -> TunnelResolution,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 1000,
    private val newId: () -> String,
) {
    var tunnels: List<TunnelEntry> by mutableStateOf(store.all().map { TunnelEntry(it) })
        private set

    init {
        // Опрос телеметрии по активным туннелям: снимаем счётчики и считаем скорость по дельте.
        scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                pollTelemetry()
            }
        }
    }

    /**
     * Перечитать список из стора. Нужно после записей в обход менеджера и при разблокировке vault:
     * на старте vault залочен и [store] (поверх vault) отдаёт пусто, после unlock данные появляются.
     * Существующие строки сохраняем по id, чтобы не потерять рантайм-состояние активных пробросов;
     * пропавшие отбрасываем, новые добавляем.
     */
    fun reload() {
        val byId = tunnels.associateBy { it.id }
        tunnels = store.all().map { tunnel -> byId[tunnel.id]?.also { it.tunnel = tunnel } ?: TunnelEntry(tunnel) }
    }

    fun find(id: String): TunnelEntry? = tunnels.firstOrNull { it.id == id }

    /**
     * Создать (если [TunnelDraft.id] == null) или обновить туннель и записать в стор. Возвращает
     * назначенный id. Правка конфига активного туннеля обновляет строку на месте, но не перезапускает
     * проброс — новые параметры подхватятся при следующем включении.
     */
    fun save(draft: TunnelDraft): String {
        val id = draft.id ?: newId()
        val tunnel = Tunnel(
            id = id,
            label = draft.label,
            hostId = draft.hostId,
            direction = draft.direction,
            bindHost = draft.bindHost,
            bindPort = draft.bindPort,
            destHost = draft.destHost,
            destPort = draft.destPort,
        )
        store.put(tunnel)
        val existing = find(id)
        if (existing != null) existing.tunnel = tunnel else tunnels = tunnels + TunnelEntry(tunnel)
        return id
    }

    /** Удалить туннель: снять, если активен, затем убрать из стора и списка. */
    fun delete(id: String) {
        deactivate(id)
        store.remove(id)
        tunnels = tunnels.filterNot { it.id == id }
    }

    /** Включить туннель: открыть соединение к хосту и поднять проброс. Идемпотентно для активного. */
    fun activate(id: String) {
        val entry = find(id) ?: return
        if (entry.status is TunnelStatus.Active || entry.status is TunnelStatus.Connecting) return
        entry.status = TunnelStatus.Connecting
        // activate зовётся с UI-потока: чтение статуса и установка Connecting синхронны (без suspend
        // между ними), поэтому повторный тап не проскочит гард. Job храним для отмены из deactivate.
        entry.connectingJob = scope.launch {
            try {
                when (val resolution = resolve(entry.tunnel)) {
                    is TunnelResolution.Unavailable -> entry.status = TunnelStatus.Failed(resolution.reason)
                    is TunnelResolution.Ready -> openForward(entry, resolution)
                }
            } finally {
                entry.connectingJob = null
            }
        }
    }

    private suspend fun openForward(entry: TunnelEntry, resolution: TunnelResolution.Ready) {
        var conn: SshConnection? = null
        try {
            // resolution.auth несёт секрет как String (техдолг SshAuth: на JVM не обнуляется); живёт на
            // стеке корутины до connect. Обнуление отложено до миграции SshAuth на байтовый буфер.
            conn = transport.connect(resolution.target, resolution.auth)
            // Туннель могли выключить, пока шёл connect — не оставляем уже открытое соединение орфаном.
            coroutineContext.ensureActive()
            val forward = raise(conn, entry.tunnel)
            entry.connection = conn
            entry.handle = forward
            entry.resetCounters()
            entry.status = TunnelStatus.Active(forward.boundPort)
        } catch (e: CancellationException) {
            closeQuietly(conn)
            throw e
        } catch (e: Exception) {
            closeQuietly(conn)
            entry.status = TunnelStatus.Failed(friendlyError(e))
        }
    }

    private suspend fun raise(conn: SshConnection, tunnel: Tunnel): PortForward = when (tunnel.direction) {
        // destHost/destPort у `-L`/`-R` обязателен (см. KDoc Tunnel) — requireNotNull, чтобы кривой
        // туннель упал явно, а не молча пробрасывался на ":0".
        TunnelDirection.Local -> conn.forwardLocal(
            LocalForwardSpec(tunnel.bindHost, tunnel.bindPort, requireDestHost(tunnel), requireDestPort(tunnel)),
        )
        TunnelDirection.Remote -> conn.forwardRemote(
            RemoteForwardSpec(tunnel.bindHost, tunnel.bindPort, requireDestHost(tunnel), requireDestPort(tunnel)),
        )
        TunnelDirection.Dynamic -> conn.forwardDynamic(
            DynamicForwardSpec(tunnel.bindHost, tunnel.bindPort),
        )
    }

    private fun requireDestHost(tunnel: Tunnel): String =
        requireNotNull(tunnel.destHost) { "Tunnel ${tunnel.direction} requires a destination host" }

    private fun requireDestPort(tunnel: Tunnel): Int =
        requireNotNull(tunnel.destPort) { "Tunnel ${tunnel.direction} requires a destination port" }

    /** Выключить туннель: закрыть проброс и его соединение, вернуть строку в [TunnelStatus.Inactive]. */
    fun deactivate(id: String) {
        val entry = find(id) ?: return
        // Отменяем подъём, если он ещё идёт: иначе connect завершится после нас и оставит орфан.
        entry.connectingJob?.cancel()
        entry.connectingJob = null
        val handle = entry.handle
        val conn = entry.connection
        entry.handle = null
        entry.connection = null
        entry.status = TunnelStatus.Inactive
        entry.resetCounters()
        if (handle != null || conn != null) {
            scope.launch {
                runCatching { handle?.close() }
                runCatching { conn?.disconnect() }
            }
        }
    }

    /** Снять все активные туннели (при выходе/локе). */
    fun closeAll() {
        tunnels.forEach { deactivate(it.id) }
    }

    internal fun pollTelemetry() {
        tunnels.forEach { entry ->
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

    private fun closeQuietly(conn: SshConnection?) {
        if (conn == null) return
        scope.launch { runCatching { conn.disconnect() } }
    }

    // Сырой текст исключения (адрес/внутренности sshj) в UI не выносим — только generic-сообщения,
    // как в runConnectionTest. Отказ ключа хоста выделен: туннель идёт по probe-верификатору, и это
    // ожидаемый исход для ещё не доверенного хоста.
    private suspend fun friendlyError(e: Exception): String = when (e) {
        is SshHostKeyRejectedException -> getString(Res.string.ptail_err_host_not_trusted)
        is SshAuthenticationException -> getString(Res.string.ptail_err_auth_failed)
        is PortForwardException -> getString(Res.string.ptail_err_forward_failed)
        is SshConnectionException -> getString(Res.string.ptail_err_connection_failed)
        else -> getString(Res.string.ptail_err_connection_failed)
    }
}
