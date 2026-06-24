package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Результат проверки «Test connection»: разовый коннект к хосту без открытия сессии.
 * [Idle] — проверка не запускалась; [Checking] — коннект в полёте; [Success] — связь установлена
 * (с round-trip в мс, если транспорт его сообщил, иначе `null`); [Failure] — с человекочитаемым
 * сообщением о причине (auth/host key/сеть).
 */
sealed interface ConnectionTestStatus {
    data object Idle : ConnectionTestStatus
    data object Checking : ConnectionTestStatus
    data class Success(val roundTripMillis: Long?) : ConnectionTestStatus
    data class Failure(val message: String) : ConnectionTestStatus
}

/**
 * Разово проверить связь с хостом: подключиться, замерить round-trip (если доступно) и сразу
 * отключиться — соединение временное, сессия не открывается. Исключения транспорта раскладываются в
 * [ConnectionTestStatus.Failure] с дружелюбным сообщением ПО КАТЕГОРИИ (auth/host key/сеть) — сырой
 * текст исключения транспорта в UI НЕ выносим, чтобы не утекли внутренности библиотеки/адрес хоста;
 * сбой самого пинга НЕ роняет тест (связь уже установлена). [CancellationException] пробрасывается
 * (кооперативная отмена не маскируется), а закрытие временного соединения выполняется безусловно
 * ([NonCancellable]), чтобы отмена не оставила сокет открытым. Чистая suspend-функция — зафиксирована
 * [app.skerry.ui.connection.ConnectionTestTest].
 */
suspend fun runConnectionTest(
    transport: SshTransport,
    target: SshTarget,
    auth: SshAuth,
): ConnectionTestStatus = try {
    val conn = transport.connect(target, auth)
    try {
        val rtt = try {
            conn.measureRoundTrip()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // пинг не удался, но коннект состоялся — это успех
        }
        ConnectionTestStatus.Success(rtt)
    } finally {
        // Закрываем безусловно: даже если корутину уже отменили, временное соединение нельзя бросить открытым.
        withContext(NonCancellable) {
            try {
                conn.disconnect()
            } catch (_: Exception) {
                // ошибку закрытия временного соединения глушим
            }
        }
    }
} catch (e: SshAuthenticationException) {
    ConnectionTestStatus.Failure("Authentication failed")
} catch (e: SshHostKeyRejectedException) {
    ConnectionTestStatus.Failure("Host key rejected")
} catch (e: SshConnectionException) {
    ConnectionTestStatus.Failure("Connection failed")
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ConnectionTestStatus.Failure("Connection failed")
}

/**
 * Compose-обёртка над [runConnectionTest]: держит [status] как state и гоняет проверку на [scope].
 * Повторный [test] отменяет предыдущую проверку; [reset] возвращает к [ConnectionTestStatus.Idle]
 * (например, при правке полей формы — старый результат больше не релевантен).
 */
@Stable
class ConnectionTestController(
    private val transport: SshTransport,
    private val scope: CoroutineScope,
) {
    var status: ConnectionTestStatus by mutableStateOf(ConnectionTestStatus.Idle)
        private set

    private var job: Job? = null

    fun test(target: SshTarget, auth: SshAuth) {
        job?.cancel()
        status = ConnectionTestStatus.Checking
        job = scope.launch {
            status = runConnectionTest(transport, target, auth)
        }
    }

    fun reset() {
        job?.cancel()
        status = ConnectionTestStatus.Idle
    }
}
