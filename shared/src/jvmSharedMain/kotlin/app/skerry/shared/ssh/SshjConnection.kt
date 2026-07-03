package app.skerry.shared.ssh

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SshjSftpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.transport.TransportException

/** Живое sshj-соединение: exec/shell/SFTP/пробросы поверх одного аутентифицированного клиента. */
internal class SshjConnection(
    private val client: SSHClient,
    override val cipher: String?,
    override val serverVersion: String?,
) : SshConnection {

    override val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    override suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            // Весь exec под таймаутом: без него зависший на сервере процесс (не закрывший stdout —
            // `tail -f`, ожидание ввода) держал бы readAtMost вечно, а cmd.join(таймаут) до него не
            // доходил. withTimeoutOrNull отменяет внутреннюю работу и отдаёт null (как measureRoundTrip),
            // не путая «тайм-аут» с внешней отменой; чтение под runInterruptible реально прерывается.
            val result = withTimeoutOrNull(EXEC_TIMEOUT_SECONDS * 1000L) {
                client.startSession().use { session ->
                    val cmd = session.exec(command)
                    // Объём капнут: недоверенный/зависший сервер не должен уметь выесть память клиента
                    // многословным выводом (в отличие от прежнего readBytes() без границы).
                    val stdout = runInterruptible { cmd.inputStream.readAtMost(MAX_EXEC_OUTPUT_BYTES) }.decodeToString()
                    val stderr = runInterruptible { cmd.errorStream.readAtMost(MAX_EXEC_OUTPUT_BYTES) }.decodeToString()
                    runInterruptible { cmd.join(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    ExecResult(exitCode = cmd.exitStatus, stdout = stdout, stderr = stderr)
                }
            }
            result ?: throw SshConnectionException("Тайм-аут выполнения команды")
        } catch (e: IOException) {
            throw SshConnectionException("Ошибка выполнения команды", e)
        }
    }

    override suspend fun measureRoundTrip(): Long? = withContext(Dispatchers.IO) {
        if (!client.isConnected) return@withContext null
        val startNanos = System.nanoTime()
        // Таймаут держим снаружи через withTimeoutOrNull: при просрочке корутина отменяется (и через
        // runInterruptible прерывает блокирующий retrieve), а наружу выходит чистый null — без
        // угадывания «ответ или таймаут» по времени. Отмену извне withTimeoutOrNull не глотает.
        withTimeoutOrNull(PING_TIMEOUT_MILLIS) {
            // sendGlobalRequest ВНЕ runInterruptible: Promise регистрируется в стейте sshj до того,
            // как мы уходим в прерываемое ожидание ответа, — прерывание не оставит «висячий» Promise
            // (на крайний случай его подберёт teardown соединения при disconnect).
            val replied = try {
                val promise = client.connection.sendGlobalRequest(KEEPALIVE_REQUEST, true, ByteArray(0))
                // keepalive@openssh.com, wantReply=true: OpenSSH отвечает SUCCESS (retrieve вернётся),
                // прочие серверы — REQUEST_FAILURE (retrieve бросит ConnectionException). Оба = round-trip.
                runInterruptible { promise.retrieve() }
                true
            } catch (e: ConnectionException) {
                true // REQUEST_FAILURE — это ОТВЕТ сервера, round-trip состоялся
            } catch (e: TransportException) {
                false // обрыв транспорта — round-trip не состоялся
            }
            if (replied) (System.nanoTime() - startNanos) / 1_000_000 else null
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

    override suspend fun openSftp(): SftpClient = withContext(Dispatchers.IO) {
        try {
            SshjSftpClient(client.newSFTPClient())
        } catch (e: IOException) {
            throw SshConnectionException("Не удалось открыть SFTP-подсистему", e)
        }
    }

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward =
        withContext(Dispatchers.IO) {
            // Каждое принятое соединение сами туннелируем через direct-tcpip-канал к destHost:destPort —
            // это даёт счётчики трафика и паузу (см. [SshjLocalForward]); штатный sshj
            // LocalPortForwarder перекачку прячет внутри.
            SshjLocalForward(client, bindListener(spec.bindHost, spec.bindPort), spec.destHost, spec.destPort)
        }

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward =
        withContext(Dispatchers.IO) {
            try {
                SshjRemoteForward.open(
                    client.remotePortForwarder,
                    RemotePortForwarder.Forward(spec.bindHost, spec.bindPort),
                    spec.destHost,
                    spec.destPort,
                )
            } catch (e: IOException) {
                throw PortForwardException(
                    "Сервер отверг обратный проброс ${spec.bindHost}:${spec.bindPort}", e,
                )
            }
        }

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward =
        withContext(Dispatchers.IO) {
            // Слушатель — как у `-L`; дальше каждое принятое соединение обслуживает SOCKS5-протокол.
            SshjDynamicForward(client, bindListener(spec.bindHost, spec.bindPort))
        }

    /**
     * Забиндить локальный слушатель для пробросов (`-L`, `-D`): биндим сами, чтобы читать фактический
     * порт при bindPort=0 и ловить «порт занят» как [PortForwardException] ещё до запуска цикла accept.
     */
    private fun bindListener(bindHost: String, bindPort: Int): ServerSocket =
        try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindHost, bindPort))
            }
        } catch (e: IOException) {
            throw PortForwardException("Не удалось занять локальный порт $bindHost:$bindPort", e)
        }

    override suspend fun disconnect() {
        // Таймаут + runInterruptible: штатно disconnect быстр, но запись SSH_MSG_DISCONNECT в уже мёртвый
        // сокет (переполненный TCP-буфер) могла бы зависнуть неопределённо долго, а вызов идёт из
        // UI-потока при закрытии вкладки. По истечении таймаута просто закрываем клиент принудительно.
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) { runInterruptible { client.disconnect() } }
                ?: runCatching { client.close() }
            Unit
        }
    }

    /** Прочитать не более [limit] байт из потока (остаток бросаем — session.use его закроет). */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private companion object {
        const val EXEC_TIMEOUT_SECONDS = 30L
        const val KEEPALIVE_REQUEST = "keepalive@openssh.com"
        const val PING_TIMEOUT_MILLIS = 5_000L
        const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
        const val MAX_EXEC_OUTPUT_BYTES = 1 * 1024 * 1024 // 1 MiB на stdout и на stderr
    }
}
