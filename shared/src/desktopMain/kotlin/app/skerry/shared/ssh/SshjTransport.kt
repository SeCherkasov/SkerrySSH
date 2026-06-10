package app.skerry.shared.ssh

import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.UserAuthException

/** Desktop-реализация [SshTransport] поверх sshj (JVM). */
class SshjTransport(
    private val hostKeyVerifier: HostKeyVerifier,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val client = SSHClient()
            var hostKeyRejected = false
            client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val trusted = hostKeyVerifier.verify(
                        host = hostname,
                        port = port,
                        keyType = KeyType.fromKey(key).toString(),
                        fingerprint = opensshFingerprint(key),
                    )
                    if (!trusted) hostKeyRejected = true
                    return trusted
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })

            try {
                client.connect(target.host, target.port)
            } catch (e: IOException) {
                client.close()
                if (hostKeyRejected) {
                    throw SshHostKeyRejectedException(
                        "Ключ хоста ${target.host}:${target.port} отвергнут верификатором",
                    )
                }
                throw SshConnectionException("Не удалось подключиться к ${target.host}:${target.port}", e)
            }

            try {
                when (auth) {
                    is SshAuth.Password -> client.authPassword(target.username, auth.secret)
                }
            } catch (e: UserAuthException) {
                client.close()
                throw SshAuthenticationException(
                    "Сервер не принял учётные данные пользователя ${target.username}", e,
                )
            } catch (e: IOException) {
                client.close()
                throw SshConnectionException("Обрыв соединения при аутентификации", e)
            }

            SshjConnection(client)
        }
}

private class SshjConnection(private val client: SSHClient) : SshConnection {

    override val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    override suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            client.startSession().use { session ->
                val cmd = session.exec(command)
                // Малые объёмы вывода; потоковое чтение появится вместе с терминалом
                val stdout = cmd.inputStream.readBytes().decodeToString()
                val stderr = cmd.errorStream.readBytes().decodeToString()
                cmd.join(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ExecResult(exitCode = cmd.exitStatus, stdout = stdout, stderr = stderr)
            }
        } catch (e: IOException) {
            throw SshConnectionException("Ошибка выполнения команды", e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        client.disconnect()
    }

    private companion object {
        const val EXEC_TIMEOUT_SECONDS = 30L
    }
}

/** Fingerprint в формате OpenSSH: `SHA256:` + base64 без паддинга от wire-кодировки ключа. */
private fun opensshFingerprint(key: PublicKey): String {
    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
