package app.skerry.shared.telnet

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection
import app.skerry.shared.ssh.StreamShellChannel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
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

/**
 * Соединение поверх одного TCP-сокета: единственный интерактивный поток (shell), без под-каналов.
 * Отсутствующие у Telnet возможности SSH (exec, SFTP, пробросы) бросает база [StreamOnlyConnection].
 */
private class TelnetConnection(private val socket: Socket) : StreamOnlyConnection("Telnet") {

    private val shellOpened = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Telnet-соединение уже открыло свой поток" }
        return TelnetShellChannel(socket, TelnetCodec(termType = term, cols = size.cols, rows = size.rows))
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
    }
}

/**
 * Интерактивный поток Telnet: читает сокет, прогоняет через [TelnetCodec] (снимает IAC-неготиацию,
 * шлёт ответы обратно в сокет — [transform]), эмитит прикладные байты в output. Запись пользователя
 * удваивает литеральный 0xFF ([TelnetCodec.encode]). Записи ответов неготиации и пользователя
 * сериализованы [writeLock], чтобы не переплести байты в общем выходном потоке сокета.
 * Каркас read-цикла/close — в базе [StreamShellChannel]; read сырого сокета не реагирует на
 * Thread.interrupt, поэтому unblockReadOnCancel = true (отмена сбора закрывает сокет).
 */
private class TelnetShellChannel(
    private val socket: Socket,
    private val codec: TelnetCodec,
) : StreamShellChannel(unblockReadOnCancel = true) {

    private val writeLock = Mutex()

    override val isOpen: Boolean
        get() = socket.isConnected && !socket.isClosed

    // Сервер сейчас не эхоит ввод (WONT ECHO) — верхний слой не пишет набранное в историю (пароли).
    override val echoSuppressed: Boolean get() = !codec.serverEchoEnabled

    override fun readBlocking(buffer: ByteArray): Int = socket.getInputStream().read(buffer)

    override fun closeSource() {
        runCatching { socket.close() }
    }

    override suspend fun transform(chunk: ByteArray): ByteArray {
        val decoded = codec.consume(chunk)
        if (decoded.reply.isNotEmpty()) writeRaw(decoded.reply)
        return decoded.data
    }

    override suspend fun write(data: ByteArray) {
        writeRaw(codec.encode(data))
        countBytesUp(data.size)
    }

    override suspend fun resize(size: PtySize) {
        // Всегда запоминаем размер в кодеке; но SB NAWS шлём ТОЛЬКО если сервер его согласовал
        // (DO NAWS) — незапрошенное под-сообщение строгий telnet-сервер может воспринять как ошибку
        // и закрыть соединение.
        val naws = codec.windowSize(size.cols, size.rows)
        if (codec.nawsNegotiated) {
            // Обрыв при отправке NAWS не критичен (размер уже запомнен в кодеке) — глотаем только
            // ошибку записи; CancellationException должна пройти наружу, это отмена, а не сбой.
            try {
                writeRaw(naws)
            } catch (_: SshConnectionException) {
            }
        }
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
}
