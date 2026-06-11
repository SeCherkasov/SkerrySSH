package app.skerry.shared.ssh

import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils

/** Desktop-реализация [SshTransport] поверх sshj (JVM). */
class SshjTransport(
    private val hostKeyVerifier: HostKeyVerifier,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val client = SSHClient()
            // verify() вызывается из IO-потока sshj, а читаем флаг из корутины после
            // connect() — нужна потокобезопасная видимость, поэтому AtomicBoolean.
            val hostKeyRejected = AtomicBoolean(false)
            client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val trusted = hostKeyVerifier.verify(
                        host = hostname,
                        port = port,
                        keyType = KeyType.fromKey(key).toString(),
                        fingerprint = opensshFingerprint(key),
                    )
                    if (!trusted) hostKeyRejected.set(true)
                    return trusted
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })

            try {
                client.connect(target.host, target.port)
            } catch (e: IOException) {
                client.close()
                if (hostKeyRejected.get()) {
                    throw SshHostKeyRejectedException(
                        "Ключ хоста ${target.host}:${target.port} отвергнут верификатором",
                    )
                }
                throw SshConnectionException("Не удалось подключиться к ${target.host}:${target.port}", e)
            }

            try {
                when (auth) {
                    is SshAuth.Password -> client.authPassword(target.username, auth.secret)
                    is SshAuth.PublicKey -> {
                        // loadKeys трактует строки как содержимое ключа (не путь); passphrase —
                        // одноразовый PasswordFinder. Формат (OpenSSH/PKCS) sshj определяет сам.
                        val pwdf = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                        val keys = client.loadKeys(auth.privateKeyPem, null, pwdf)
                        client.authPublickey(target.username, keys)
                    }
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

    override suspend fun openShell(size: PtySize, term: String): ShellChannel =
        withContext(Dispatchers.IO) {
            try {
                val session = client.startSession()
                session.allocatePTY(term, size.cols, size.rows, size.widthPx, size.heightPx, emptyMap())
                SshjShellChannel(session, session.startShell())
            } catch (e: IOException) {
                throw SshConnectionException("Не удалось открыть shell-канал", e)
            }
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        client.disconnect()
    }

    private companion object {
        const val EXEC_TIMEOUT_SECONDS = 30L
    }
}

private class SshjShellChannel(
    private val session: Session,
    private val shell: Session.Shell,
) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = session.isOpen

    override val output: Flow<ByteArray> = flow {
        check(outputClaimed.compareAndSet(false, true)) {
            "ShellChannel.output поддерживает только одного сборщика"
        }
        val stream = shell.inputStream
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            // runInterruptible: блокирующий read должен прерываться отменой корутины,
            // иначе IO-поток виснет в read навсегда и держит runBlocking. EOF либо
            // прерывание потока (close() закрывает stream) роняют read как IOException.
            val read = try {
                runInterruptible(Dispatchers.IO) { stream.read(buffer) }
            } catch (_: IOException) {
                break
            }
            if (read < 0) break
            if (read > 0) emit(buffer.copyOf(read))
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            shell.outputStream.write(data)
            shell.outputStream.flush()
        } catch (e: IOException) {
            throw SshConnectionException("Запись в shell-канал не удалась", e)
        }
    }

    override suspend fun resize(size: PtySize) = withContext(Dispatchers.IO) {
        try {
            shell.changeWindowDimensions(size.cols, size.rows, size.widthPx, size.heightPx)
        } catch (e: IOException) {
            throw SshConnectionException("Не удалось изменить размер PTY", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        // Идемпотентно: повторный close() (например, из close-обработчика и из
        // EOF-пути одновременно) не должен повторно дёргать teardown.
        if (!closed.compareAndSet(false, true)) return@withContext
        // Закрываем входной поток первым, чтобы разблокировать read в output;
        // только потом рвём сам канал. Цикл сбора в output читает лишь shell.inputStream
        // и не обращается к session, поэтому session.close() безопасен даже до того,
        // как read разблокировался. runCatching — teardown не должен бросать наружу.
        runCatching { shell.inputStream.close() }
        runCatching { session.close() }
        Unit
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}

/** Fingerprint в формате OpenSSH: `SHA256:` + base64 без паддинга от wire-кодировки ключа. */
private fun opensshFingerprint(key: PublicKey): String {
    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
