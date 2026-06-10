package app.skerry.shared.ssh

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

    /** Одноразовый exec-канал; интерактивный shell появится с терминалом. */
    suspend fun exec(command: String): ExecResult

    suspend fun disconnect()
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
