package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.ShellTerminalSession
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.metrics.HostMetricsController
import app.skerry.ui.terminal.TerminalScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Состояние экрана подключения. */
sealed interface ConnectionUiState {
    /** Показывается форма подключения (старт или возврат после отключения/ошибки). */
    data object Form : ConnectionUiState

    /** Идёт connect/auth/открытие shell. */
    data object Connecting : ConnectionUiState

    /** Сессия открыта; [terminal] — состояние живого терминала. */
    data class Connected(val terminal: TerminalScreenState) : ConnectionUiState

    /** Подключиться не удалось; [message] для показа пользователю. */
    data class Error(val message: String) : ConnectionUiState
}

/**
 * Связывает форму подключения с [SshTransport]: по [connect] устанавливает соединение,
 * открывает интерактивный shell и собирает [TerminalScreenState] поверх [ShellTerminalSession].
 *
 * Лайфтайм сбора вывода и декода живёт в отдельной session-scope (см. [newSessionScope]):
 * [disconnect] отменяет её и рвёт соединение, не трогая основной [scope] контроллера.
 * Тесты подменяют [newSessionScope] тестовым диспетчером для детерминизма.
 *
 * Переходы инициируются только из [ConnectionUiState.Form] (guard в [connect]), поэтому
 * параллельных connect быть не может. [disconnect] отменяет незавершённый connect и
 * закрывает уже установленное соединение; teardown идёт под [NonCancellable], чтобы не
 * потеряться при отмене основного scope.
 */
@Stable
class ConnectionController(
    private val transport: SshTransport,
    private val scope: CoroutineScope,
    private val newSessionScope: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    },
) {
    var uiState: ConnectionUiState by mutableStateOf(ConnectionUiState.Form)
        private set

    /**
     * Согласованный шифр живого соединения этой сессии (для info-панели) или `null`, пока сессия не
     * подключена / транспорт его не сообщает. Хранится snapshot-стейтом (а не геттером по
     * не-snapshot [connection]), чтобы Compose отслеживал чтение и перерисовывал info-панель при
     * появлении/сбросе соединения. Выставляется при переходе в [ConnectionUiState.Connected].
     */
    var cipher: String? by mutableStateOf(null)
        private set

    private var connectJob: Job? = null
    private var connection: SshConnection? = null
    private var sessionScope: CoroutineScope? = null
    private var portForwards: PortForwardController? = null
    private var sftpClient: SftpClient? = null
    private var transferCoordinator: TransferCoordinator? = null
    private val sftpMutex = Mutex()
    private var metrics: HostMetricsController? = null

    fun connect(target: SshTarget, auth: SshAuth) {
        // Стартуем только из формы: пока идёт подключение или есть открытая сессия,
        // повторный connect игнорируется — иначе можно утечь scope и соединение.
        if (uiState !is ConnectionUiState.Form) return
        uiState = ConnectionUiState.Connecting
        connectJob = scope.launch {
            var conn: SshConnection? = null
            try {
                conn = transport.connect(target, auth)
                ensureActive()
                val channel = conn.openShell()
                ensureActive()
                val sScope = newSessionScope()
                connection = conn
                cipher = conn.cipher
                sessionScope = sScope
                uiState = ConnectionUiState.Connected(
                    TerminalScreenState(ShellTerminalSession(channel, sScope), sScope),
                )
            } catch (e: CancellationException) {
                // disconnect() во время подключения: закрываем полуоткрытое соединение,
                // uiState уже выставлен в Form самим disconnect().
                conn?.let(::closeConnectionQuietly)
                throw e
            } catch (e: Exception) {
                conn?.let(::closeConnectionQuietly)
                uiState = ConnectionUiState.Error(e.message ?: "Не удалось подключиться")
            }
        }
    }

    /**
     * Открыть SFTP-канал поверх живого соединения этой сессии. Канал — собственность вызывающего
     * (экран SFTP): закрывать через [app.skerry.shared.sftp.SftpClient.close] в dispose. Само
     * SSH-соединение остаётся за контроллером и [disconnect] его закроет.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    suspend fun openSftp(): SftpClient =
        (connection ?: error("Нет активного соединения для SFTP")).openSftp()

    /**
     * Контроллер проброса портов этой сессии — один на соединение, создаётся лениво и кэшируется,
     * поэтому переживает переключение вкладок/панелей UI (туннели живут, пока жива сессия). Операции
     * гоняются на внутреннем [scope] сессии (как у [openTransferCoordinator]), а не на UI-scope экрана —
     * иначе уход вью из композиции отменил бы scope уже закэшированного контроллера и тихо убил бы
     * подъём/снятие туннелей. Все пробросы снимает [disconnect] при закрытии сессии.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    fun openPortForwards(): PortForwardController =
        portForwards ?: PortForwardController(
            connection ?: error("Нет активного соединения для проброса портов"),
            scope,
        ).also { portForwards = it }

    /**
     * Двухпанельный SFTP-координатор этой сессии (локальная ФС + удалённый хост) — один на соединение,
     * создаётся лениво и кэшируется (как [openPortForwards]), поэтому переживает переключение view
     * (путь/выделение панелей не сбрасываются). Операции панелей и передачи гоняются на внутреннем
     * [scope] сессии, а сам канал ([sftpClient]) закрывает [disconnect]. Первый вызов открывает канал
     * и запускает загрузку стартовых каталогов обеих панелей ([FilePaneController.start]). [localBrowser]
     * — платформенный браузер локальной ФС (его поставляет UI-слой, чтобы контроллер не зависел от
     * платформенных expect-функций и оставался тестируемым); [hostLabel] — метка удалённой панели.
     * Оба параметра используются лишь при первом создании — повторный вызов отдаёт кэш и их игнорирует.
     * [sftpMutex] сериализует ленивую инициализацию: даже при гонке двух вызывающих канал откроется
     * один раз (без утечки второго), а не-volatile поля кэша безопасно публикуются под локом.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    suspend fun openTransferCoordinator(localBrowser: FileBrowser, hostLabel: String): TransferCoordinator =
        sftpMutex.withLock {
            transferCoordinator ?: run {
                val client = (connection ?: error("Нет активного соединения для SFTP")).openSftp()
                sftpClient = client
                TransferCoordinator(
                    sftp = client,
                    local = FilePaneController(localBrowser, scope),
                    remote = FilePaneController(SftpFileBrowser(client, hostLabel), scope),
                    scope = scope,
                ).also {
                    it.local.start()
                    it.remote.start()
                    transferCoordinator = it
                }
            }
        }

    /**
     * Контроллер live-метрик хоста этой сессии — один на соединение, создаётся лениво и кэшируется
     * (как [openPortForwards]/[openTransferCoordinator]), опрос гоняется на [scope] сессии и стартует
     * сразу. Останавливается в [disconnect] вместе с сессией.
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    fun openMetrics(): HostMetricsController {
        val conn = connection ?: error("Нет активного соединения для метрик")
        return metrics ?: HostMetricsController(
            exec = { cmd -> conn.exec(cmd) },
            scope = scope,
        ).also { it.start(); metrics = it }
    }

    /** Закрыть сессию (если есть) и вернуться к форме. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val conn = connection
        portForwards?.closeAll()
        portForwards = null
        val sftp = sftpClient
        sftpClient = null
        transferCoordinator = null
        metrics?.stop()
        metrics = null
        sessionScope?.cancel()
        sessionScope = null
        connection = null
        cipher = null
        if (sftp != null) closeSftpQuietly(sftp)
        if (conn != null) closeConnectionQuietly(conn)
        uiState = ConnectionUiState.Form
    }

    /** Сбросить ошибку и вернуться к форме. */
    fun dismissError() {
        if (uiState is ConnectionUiState.Error) uiState = ConnectionUiState.Form
    }

    /** Закрыть соединение, не давая отмене scope сорвать teardown и не пробрасывая ошибки. */
    private fun closeConnectionQuietly(conn: SshConnection) {
        scope.launch(NonCancellable) { runCatching { conn.disconnect() } }
    }

    /** Закрыть SFTP-канал в фоне под [NonCancellable] (SSH-соединение закроется следом отдельно). */
    private fun closeSftpQuietly(client: SftpClient) {
        scope.launch(NonCancellable) { runCatching { client.close() } }
    }
}
