package app.skerry.shared.ssh

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder

/**
 * Перекачать поток до EOF, сбрасывая буфер после каждого чанка (нужно для интерактивного TCP), и
 * прибавить число прокачанных байт к [counter]. Считаем сразу после чтения (до записи), чтобы
 * счётчик был не позади видимых получателю данных — на это опираются тесты телеметрии.
 */
private fun pump(input: InputStream, output: OutputStream, counter: AtomicLong) {
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        counter.addAndGet(n.toLong())
        output.write(buf, 0, n)
        output.flush()
    }
}

/**
 * Двунаправленная перекачка между принятым локальным соединением ([near]) и SSH-каналом ([far]):
 * восходящий поток (near→far) крутится на отдельном демоническом потоке, нисходящий (far→near) — на
 * вызывающем. [up] считает байты, ушедшие в канал (к серверу), [down] — пришедшие из канала. По концу
 * ввода near полузакрываем write-сторону канала: сервер увидит EOF и закроет назначение, нисходящий
 * поток завершится. Возврат — после завершения обоих направлений.
 */
private fun tunnel(near: Socket, far: Channel, up: AtomicLong, down: AtomicLong, name: String) {
    val upstream = thread(isDaemon = true, name = "$name-up") {
        runCatching { pump(near.getInputStream(), far.outputStream, up); far.outputStream.close() }
    }
    runCatching { pump(far.inputStream, near.getOutputStream(), down) }
    upstream.join()
}

/**
 * Общий стейт проброса: флаги активности/паузы, счётчики трафика и множество живых ресурсов
 * поднятых туннелей. Выделен, чтобы [AcceptingForward] (`-L`/`-D`, слушатель у нас) и
 * [SshjRemoteForward] (`-R`, слушатель у сервера) не дублировали телеметрию и хореографию
 * pause/close. Все поля потокобезопасны: пишутся из потоков туннелей, читаются из корутин UI.
 */
internal class ForwardState {
    val active = AtomicBoolean(true)
    val paused = AtomicBoolean(false)
    val up = AtomicLong(0)
    val down = AtomicLong(0)
    val live: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

    /** Снять все живые ресурсы (уже поднятые туннели/каналы); ошибки закрытия глотаются. */
    fun closeAll() {
        live.toList().forEach { runCatching { it.close() } }
    }
}

/**
 * База для пробросов со слушателем на нашей стороне (`-L`, `-D`). Держит [serverSocket], крутит accept
 * на демоническом потоке, на каждое соединение запускает [handle] в своём потоке. Несёт паузу (на
 * паузе принятое соединение сразу рвём, порт держим) и счётчики трафика в [state], которые наполняет
 * [handle] через [tunnel]. [close] закрывает слушатель и все живые туннели.
 *
 * Поток accept НЕ запускается в конструкторе базы (иначе [handle] мог бы сработать на ещё не
 * достроенном подклассе) — подкласс вызывает [startAccepting] в конце своего init.
 */
internal abstract class AcceptingForward(
    private val serverSocket: ServerSocket,
    private val threadName: String,
) : PortForward {

    protected val state = ForwardState()

    final override val boundPort: Int = serverSocket.localPort
    final override val isActive: Boolean get() = state.active.get() && !serverSocket.isClosed
    final override val isPaused: Boolean get() = state.paused.get()
    final override val bytesUp: Long get() = state.up.get()
    final override val bytesDown: Long get() = state.down.get()

    private lateinit var acceptor: Thread

    protected fun startAccepting() {
        acceptor = thread(isDaemon = true, name = "$threadName-$boundPort") {
            while (state.active.get() && !serverSocket.isClosed) {
                // accept роняется IOException при close() — штатное завершение цикла.
                val socket = try { serverSocket.accept() } catch (e: IOException) { break }
                // На паузе порт держим, но соединение сразу рвём — туннель не поднимаем.
                if (state.paused.get()) { runCatching { socket.close() }; continue }
                thread(isDaemon = true, name = "$threadName-conn-$boundPort") { handle(socket) }
            }
        }
    }

    /** Обслужить принятое соединение: открыть SSH-канал к назначению и прокачать байты через [tunnel]. */
    protected abstract fun handle(socket: Socket)

    final override suspend fun pause() = withContext(Dispatchers.IO) { state.paused.set(true) }
    final override suspend fun resume() = withContext(Dispatchers.IO) { state.paused.set(false) }

    final override suspend fun close() = withContext(Dispatchers.IO) {
        if (!state.active.compareAndSet(true, false)) return@withContext
        runCatching { serverSocket.close() } // рвёт accept
        state.closeAll() // снять уже поднятые туннели
        acceptor.join(CLOSE_JOIN_MILLIS)
        Unit
    }

    protected companion object {
        const val CLOSE_JOIN_MILLIS = 1000L
    }
}

/**
 * Локальный проброс (`-L`): сами принимаем соединения на слушателе и под каждое открываем
 * direct-tcpip-канал к фиксированному [destHost]:[destPort] (адрес разрешает сервер), затем
 * двунаправленно перекачиваем байты. Собственная перекачка (вместо штатного sshj LocalPortForwarder,
 * прятавшего поток внутри) даёт счётчики трафика и паузу.
 */
internal class SshjLocalForward(
    private val client: SSHClient,
    serverSocket: ServerSocket,
    private val destHost: String,
    private val destPort: Int,
) : AcceptingForward(serverSocket, "skerry-local-forward") {

    init { startAccepting() }

    override fun handle(socket: Socket) {
        var channel: DirectConnection? = null
        state.live.add(socket)
        try {
            socket.tcpNoDelay = true
            channel = client.newDirectConnection(destHost, destPort)
            val ch = channel
            state.live.add(ch)
            tunnel(socket, ch, state.up, state.down, "skerry-local-$boundPort")
        } catch (e: Exception) {
            // Обрыв соединения/канала — штатное завершение туннеля.
        } finally {
            channel?.let { state.live.remove(it); runCatching { it.close() } }
            state.live.remove(socket)
            runCatching { socket.close() }
        }
    }
}

/**
 * Динамический проброс (`-D`): на слушателе держим SOCKS5-сервер. Каждое соединение проводит
 * SOCKS5-хэндшейк ([Socks5]) и под запрошенный адрес открывает direct-tcpip-канал, затем
 * двунаправленно перекачивает байты со счётом трафика.
 */
internal class SshjDynamicForward(
    private val client: SSHClient,
    serverSocket: ServerSocket,
) : AcceptingForward(serverSocket, "skerry-socks") {

    init { startAccepting() }

    override fun handle(socket: Socket) {
        var channel: DirectConnection? = null
        state.live.add(socket)
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val target = Socks5.accept(input, output) ?: return // отказ уже отправлен
            channel = try {
                client.newDirectConnection(target.host, target.port)
            } catch (e: IOException) {
                Socks5.replyFailure(output, Socks5.REP_CONNECTION_REFUSED)
                return
            }
            val ch = channel
            state.live.add(ch)
            Socks5.replySuccess(output)
            tunnel(socket, ch, state.up, state.down, "skerry-socks-$boundPort")
        } catch (e: Exception) {
            // Обрыв соединения/канала — штатное завершение туннеля.
        } finally {
            channel?.let { state.live.remove(it); runCatching { it.close() } }
            state.live.remove(socket)
            runCatching { socket.close() }
        }
    }
}

/**
 * Обратный проброс (`-R`): слушатель держит сервер. Каждое входящее соединение sshj отдаёт нашему
 * ConnectListener каналом [Channel.Forwarded]; под него мы открываем локальный сокет к
 * [destHost]:[destPort] и двунаправленно перекачиваем байты со счётом трафика и поддержкой паузы. На
 * паузе входящий канал сразу закрываем (новые соединения не туннелируем). [close] отменяет привязку на
 * сервере и рвёт живые туннели. Создаётся через [open], которая биндит проброс и узнаёт назначенный порт.
 */
internal class SshjRemoteForward private constructor(
    private val forwarder: RemotePortForwarder,
    private val destHost: String,
    private val destPort: Int,
) : PortForward {

    private val state = ForwardState()
    private lateinit var forward: RemotePortForwarder.Forward

    override var boundPort: Int = 0
        private set

    override val isActive: Boolean get() = state.active.get()
    override val isPaused: Boolean get() = state.paused.get()
    override val bytesUp: Long get() = state.up.get()
    override val bytesDown: Long get() = state.down.get()

    private fun gotConnect(channel: Channel.Forwarded) {
        // На паузе/после снятия отвергаем входящий канал (сервер увидит отказ), а не молча закрываем.
        if (!state.active.get() || state.paused.get()) {
            runCatching { channel.reject(OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED, "tunnel paused") }
            return
        }
        thread(isDaemon = true, name = "skerry-remote-conn-$boundPort") { handle(channel) }
    }

    private fun handle(channel: Channel.Forwarded) {
        state.live.add(channel)
        // Сначала соединяемся с локальным назначением; не вышло — отвергаем канал и выходим.
        val socket = try {
            Socket(destHost, destPort).apply { tcpNoDelay = true }
        } catch (e: IOException) {
            runCatching { channel.reject(OpenFailException.Reason.CONNECT_FAILED, "destination unreachable") }
            state.live.remove(channel)
            runCatching { channel.close() }
            return
        }
        state.live.add(socket)
        try {
            // Подтверждаем открытие forwarded-канала — без этого сервер не начнёт слать данные.
            channel.confirm()
            // near = локальный сокет назначения, far = канал от сервера: up — ответ назначения в канал
            // (к серверу), down — данные удалённого клиента из канала к назначению.
            tunnel(socket, channel, state.up, state.down, "skerry-remote-$boundPort")
        } catch (e: Exception) {
            // Обрыв соединения/канала — штатное завершение туннеля.
        } finally {
            state.live.remove(socket)
            runCatching { socket.close() }
            state.live.remove(channel)
            runCatching { channel.close() }
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) { state.paused.set(true) }
    override suspend fun resume() = withContext(Dispatchers.IO) { state.paused.set(false) }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!state.active.compareAndSet(true, false)) return@withContext
        runCatching { forwarder.cancel(forward) }
        state.closeAll()
        Unit
    }

    companion object {
        /** Забиндить обратный проброс на сервере с нашим ConnectListener и вернуть готовый [PortForward]. */
        fun open(
            forwarder: RemotePortForwarder,
            forwardSpec: RemotePortForwarder.Forward,
            destHost: String,
            destPort: Int,
        ): SshjRemoteForward {
            val pf = SshjRemoteForward(forwarder, destHost, destPort)
            val bound = forwarder.bind(
                forwardSpec,
                ConnectListener { channel -> pf.gotConnect(channel) },
            )
            pf.forward = bound
            pf.boundPort = bound.port
            return pf
        }
    }
}
