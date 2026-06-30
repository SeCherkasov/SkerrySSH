package app.skerry.shared.telnet

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Telnet-транспорт (RFC 854) поверх обычного TCP-сокета. Живёт в общем JVM-узле (desktop + Android),
 * как и sshj. Аутентификации у Telnet нет: [SshAuth] игнорируется, логин/пароль вводятся в самом
 * терминале как обычный поток данных. Возможности SSH, которых у Telnet нет (SFTP, проброс портов,
 * exec, метрики шифра), помечены как неподдерживаемые и бросают [UnsupportedOperationException].
 */
class TelnetTransport(
    private val connectTimeoutMillis: Int = 15_000,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMillis)
                socket.tcpNoDelay = true // интерактивный терминал: без Nagle, посимвольная отзывчивость
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw SshConnectionException("Не удалось подключиться к ${target.host}:${target.port}", e)
            }
            TelnetConnection(socket)
        }
}

/** Соединение поверх одного TCP-сокета: единственный интерактивный поток (shell), без под-каналов. */
private class TelnetConnection(private val socket: Socket) : SshConnection {

    private val shellOpened = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Telnet-соединение уже открыло свой поток" }
        return TelnetShellChannel(socket, TelnetCodec(termType = term, cols = size.cols, rows = size.rows))
    }

    override suspend fun exec(command: String): ExecResult =
        throw UnsupportedOperationException("Telnet не поддерживает exec-каналы")

    override suspend fun openSftp(): SftpClient =
        throw UnsupportedOperationException("Telnet не поддерживает SFTP")

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward =
        throw UnsupportedOperationException("Telnet не поддерживает проброс портов")

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward =
        throw UnsupportedOperationException("Telnet не поддерживает проброс портов")

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward =
        throw UnsupportedOperationException("Telnet не поддерживает проброс портов")

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
    }
}

/**
 * Интерактивный поток Telnet: читает сокет, прогоняет через [TelnetCodec] (снимает IAC-неготиацию,
 * шлёт ответы обратно в сокет), эмитит прикладные байты в [output]. Запись пользователя удваивает
 * литеральный 0xFF ([TelnetCodec.encode]). Записи ответов неготиации и пользователя сериализованы
 * [writeLock], чтобы не переплести байты в общем выходном потоке сокета.
 */
private class TelnetShellChannel(
    private val socket: Socket,
    private val codec: TelnetCodec,
) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val eofReached = AtomicBoolean(false)
    override val endedWithEof: Boolean get() = eofReached.get()

    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    override val bytesUp: Long get() = _bytesUp.get()
    override val bytesDown: Long get() = _bytesDown.get()

    private val writeLock = Mutex()

    override val isOpen: Boolean
        get() = socket.isConnected && !socket.isClosed

    // Сервер сейчас не эхоит ввод (WONT ECHO) — верхний слой не пишет набранное в историю (пароли).
    override val echoSuppressed: Boolean get() = !codec.serverEchoEnabled

    override val output: Flow<ByteArray> = flow {
        check(outputClaimed.compareAndSet(false, true)) {
            "ShellChannel.output поддерживает только одного сборщика"
        }
        // Блокирующий read сырого сокета НЕ реагирует на Thread.interrupt (в отличие от sshj-очереди),
        // поэтому отмена сбора сама его не разбудит. Закрываем сокет из обработчика завершения Job —
        // он срабатывает на отмену независимо от заблокированного IO-потока и роняет read как IOException.
        val disposable = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { socket.close() } }
        try {
            val stream = socket.getInputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = try {
                    runInterruptible(Dispatchers.IO) { stream.read(buffer) }
                } catch (_: IOException) {
                    break
                }
                if (read < 0) {
                    eofReached.set(true) // сервер закрыл соединение штатно
                    break
                }
                if (read == 0) continue
                _bytesDown.addAndGet(read.toLong())
                val decoded = codec.consume(buffer.copyOf(read))
                if (decoded.reply.isNotEmpty()) writeRaw(decoded.reply)
                if (decoded.data.isNotEmpty()) emit(decoded.data)
            }
        } finally {
            disposable?.dispose()
        }
    }

    override suspend fun write(data: ByteArray) {
        writeRaw(codec.encode(data))
        _bytesUp.addAndGet(data.size.toLong())
    }

    override suspend fun resize(size: PtySize) {
        // Всегда запоминаем размер в кодеке; но SB NAWS шлём ТОЛЬКО если сервер его согласовал
        // (DO NAWS) — незапрошенное под-сообщение строгий telnet-сервер может воспринять как ошибку
        // и закрыть соединение.
        val naws = codec.windowSize(size.cols, size.rows)
        if (codec.nawsNegotiated) runCatching { writeRaw(naws) }
    }

    private suspend fun writeRaw(bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                val out = socket.getOutputStream()
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                throw SshConnectionException("Запись в Telnet-поток не удалась", e)
            }
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        // Закрываем сокет: разблокирует read в output и завершает поток.
        runCatching { socket.close() }
        Unit
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
