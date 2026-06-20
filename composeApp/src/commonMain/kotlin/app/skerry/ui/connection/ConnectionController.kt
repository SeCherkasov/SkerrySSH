package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.ShellTerminalSession
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.terminal.TerminalScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    private var connectJob: Job? = null
    private var connection: SshConnection? = null
    private var sessionScope: CoroutineScope? = null

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
     * Создать контроллер проброса портов поверх живого соединения. Соединение остаётся за этим
     * контроллером ([disconnect] его закроет); пробросы закрывает сам [PortForwardController]
     * (через свои [PortForwardController.remove]/[PortForwardController.closeAll]).
     * @throws IllegalStateException сессия не подключена (нет живого соединения)
     */
    fun openPortForwards(scope: CoroutineScope): PortForwardController =
        PortForwardController(
            connection ?: error("Нет активного соединения для проброса портов"),
            scope,
        )

    /** Закрыть сессию (если есть) и вернуться к форме. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val conn = connection
        sessionScope?.cancel()
        sessionScope = null
        connection = null
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
}
