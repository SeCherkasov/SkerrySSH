package app.skerry.shared.ssh

/**
 * Транспортный контракт SSH-ядра. Платформенные реализации подставляются снаружи:
 * на desktop — sshj (JVM), на мобильных — своя реализация позже.
 *
 * Дизайн API — следующий шаг (первый SSH-коннект на desktop); здесь только каркас.
 */
interface SshTransport {
    suspend fun connect(target: SshTarget): SshConnection
}

data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
)

interface SshConnection {
    val isConnected: Boolean
    suspend fun disconnect()
}
