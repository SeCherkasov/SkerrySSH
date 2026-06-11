package app.skerry.shared.ssh

import kotlinx.coroutines.flow.Flow

/**
 * Транспортный контракт SSH-ядра. Платформенные реализации подставляются снаружи:
 * на desktop — sshj (JVM), на мобильных — своя реализация позже.
 */
interface SshTransport {
    /**
     * @throws SshConnectionException сетевая ошибка или обрыв транспорта
     * @throws SshHostKeyRejectedException ключ хоста отвергнут [HostKeyVerifier]
     * @throws SshAuthenticationException сервер не принял учётные данные
     */
    suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection
}

data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
)

sealed interface SshAuth {
    // Секрет как String до появления vault; там же появится зануление памяти
    data class Password(val secret: String) : SshAuth
}

/**
 * Решение о доверии ключу хоста. Fingerprint — в формате OpenSSH
 * (`SHA256:` + base64 без паддинга), keyType — идентификатор алгоритма
 * (`ssh-ed25519`, `rsa-sha2-512`, …). Персистентный known-hosts появится
 * вместе с менеджером хостов.
 */
fun interface HostKeyVerifier {
    fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean
}

interface SshConnection {
    val isConnected: Boolean

    /** Одноразовый exec-канал для неинтерактивных команд. */
    suspend fun exec(command: String): ExecResult

    /**
     * Интерактивный shell с PTY.
     * @throws SshConnectionException канал открыть не удалось
     */
    suspend fun openShell(size: PtySize = PtySize(), term: String = "xterm-256color"): ShellChannel

    suspend fun disconnect()
}

/** Размер PTY; пиксельные размеры опциональны (0 — не сообщать). */
data class PtySize(
    val cols: Int = 80,
    val rows: Int = 24,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)

interface ShellChannel {
    val isOpen: Boolean

    /**
     * Сырой вывод PTY (stdout и stderr слиты, как в реальном терминале).
     * Холодный flow с единственным разрешённым сборщиком: повторный collect
     * бросает [IllegalStateException]. Завершается на EOF канала.
     */
    val output: Flow<ByteArray>

    /** @throws SshConnectionException канал закрыт или обрыв транспорта */
    suspend fun write(data: ByteArray)

    suspend fun resize(size: PtySize)

    suspend fun close()
}

data class ExecResult(
    /** null, если сервер закрыл канал без статуса. */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

open class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SshConnectionException(message: String, cause: Throwable? = null) : SshException(message, cause)

class SshHostKeyRejectedException(message: String) : SshException(message)

class SshAuthenticationException(message: String, cause: Throwable? = null) : SshException(message, cause)
