package app.skerry.shared.ssh

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SshjSftpClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils

/** Desktop-реализация [SshTransport] поверх sshj (JVM). */
class SshjTransport(
    private val hostKeyVerifier: HostKeyVerifier,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            ensureCryptoProvider()
            val client = SSHClient()
            // TCP connect-timeout: у sshj дефолт 0 = ждать бесконечно. Без него «Test connection» к
            // несуществующему/закрытому файрволом адресу висит без возможности отмены через UI.
            // (Протокольный таймаут KEX/I/O — отдельно, дефолт sshj ~30 c; пинг round-trip — свой.)
            client.connectTimeout = CONNECT_TIMEOUT_MILLIS
            // Согласованный при KEX шифр (client→server) перехватываем верификатором алгоритмов:
            // в sshj 0.40 он вызывается синхронно на IO-потоке внутри connect() (после NEWKEYS, до
            // возврата), а читаем после connect() — нужна потокобезопасная публикация, поэтому
            // AtomicReference. Верификатор всегда пропускает (true): проверкой шифров не занимаемся,
            // только снимаем имя для info-панели; host-key проверка — отдельная цепочка (addHostKeyVerifier).
            val negotiatedCipher = AtomicReference<String?>(null)
            client.transport.addAlgorithmsVerifier { negotiated ->
                negotiatedCipher.set(negotiated.client2ServerCipherAlgorithm)
                true
            }
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
                // Адрес хоста в текст сообщения не выносим (логи/краш-репортеры): метаданные коннекта
                // чувствительны в zero-knowledge клиенте. Диагностический детайл остаётся в cause (e).
                if (hostKeyRejected.get()) {
                    throw SshHostKeyRejectedException("Ключ хоста отвергнут верификатором")
                }
                throw SshConnectionException("Не удалось установить соединение", e)
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
                    is SshAuth.Certificate -> {
                        // Cert-auth: владение доказываем приватным ключом из PEM, а серверу предъявляем
                        // сам сертификат (публичная часть = распарсенный *-cert.pub). sshj не склеивает
                        // их из строк сам (только из файлов по соседству), поэтому собираем KeyProvider
                        // вручную: private — из PEM, public — Certificate, type — *_CERT.
                        val pwdf = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                        val keys = client.loadKeys(auth.privateKeyPem, null, pwdf)
                        client.authPublickey(target.username, certificateKeyProvider(keys, auth.certificate))
                    }
                }
            } catch (e: UserAuthException) {
                client.close()
                // Без имени пользователя в тексте: сообщение не должно нести идентификатор (логи/отчёты).
                throw SshAuthenticationException("Сервер не принял учётные данные", e)
            } catch (e: IOException) {
                client.close()
                throw SshConnectionException("Обрыв соединения при аутентификации", e)
            }

            // Ident сервера sshj отдаёт без префикса (`getServerVersion()` = serverID.substring(8)),
            // восстанавливаем полную форму `SSH-2.0-<software>` как в статус-баре. Читаем синхронно
            // на этом же IO-потоке после connect() — identification exchange уже завершён, гонки нет.
            // (Вымерший `SSH-1.99-` сервер отобразился бы как `SSH-2.0-` — substring(8) одинаков; косметика.)
            val serverVersion = runCatching { client.transport.serverVersion }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { "SSH-2.0-$it" }
            SshjConnection(client, negotiatedCipher.get(), serverVersion)
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
    }
}

/** Один раз на процесс: регистрация полного BouncyCastle (см. [ensureCryptoProvider]). */
private val cryptoProviderLock = Any()

@Volatile
private var cryptoProviderReady = false

/**
 * sshj полагается на полный BouncyCastle. На Android в провайдере «BC» по умолчанию сидит урезанный
 * системный BouncyCastle (класс `com.android.org.bouncycastle…`), которому не хватает шифров и
 * обмена ключами, нужных sshj, — из-за этого `connect()` падает на этапе KEX с обычным `IOException`
 * («Не удалось подключиться к host:port»). Подменяем «BC» на полноценный провайдер из bcprov,
 * который бандлится с sshj. На desktop JVM проблемы нет — guard по наличию `android.os.Build`
 * делает функцию no-op, так что рабочее поведение desktop не меняется. Идемпотентно.
 *
 * `internal` (а не `private`): тот же урезанный системный BouncyCastle ломает не только KEX при
 * коннекте, но и разбор приватного ключа (`SSHClient.loadKeys` в [app.skerry.shared.vault.BouncyCastleSshKeyGenerator.inspect]),
 * поэтому генератор/инспектор ключей раздела Vault регистрирует полный провайдер этим же вызовом.
 *
 * Под [synchronized] (а не lock-free `compareAndSet`): флаг `cryptoProviderReady` поднимаем ТОЛЬКО
 * после фактической регистрации провайдера. Иначе второй поток (например, `inspect` из таба Vault и
 * `connect()` одновременно) увидел бы поднятый флаг и начал использовать ещё урезанный «BC» в окне
 * между взведением флага и `insertProviderAt`. Двойная проверка флага оставляет общий путь без лока.
 */
internal fun ensureCryptoProvider() {
    if (cryptoProviderReady) return
    synchronized(cryptoProviderLock) {
        if (cryptoProviderReady) return
        val onAndroid = runCatching { Class.forName("android.os.Build") }.isSuccess
        if (onAndroid) {
            val existing = Security.getProvider("BC")
            if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
        cryptoProviderReady = true
    }
}

private class SshjConnection(
    private val client: SSHClient,
    override val cipher: String?,
    override val serverVersion: String?,
) : SshConnection {

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
            // Слушатель биндим сами: так читаем фактический порт при bindPort=0 и ловим «порт занят»
            // как PortForwardException ещё до запуска цикла accept. Каждое принятое соединение сами
            // туннелируем через direct-tcpip-канал к destHost:destPort — это даёт счётчики трафика и
            // паузу (см. [SshjLocalForward]); штатный sshj LocalPortForwarder перекачку прячет внутри.
            val serverSocket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(spec.bindHost, spec.bindPort))
                }
            } catch (e: IOException) {
                throw PortForwardException(
                    "Не удалось занять локальный порт ${spec.bindHost}:${spec.bindPort}", e,
                )
            }
            SshjLocalForward(client, serverSocket, spec.destHost, spec.destPort)
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
            // Слушатель биндим сами (как у `-L`): читаем фактический порт при bindPort=0 и ловим
            // «порт занят» сразу. Дальше каждое принятое соединение обслуживает SOCKS5-протокол.
            val serverSocket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(spec.bindHost, spec.bindPort))
                }
            } catch (e: IOException) {
                throw PortForwardException(
                    "Не удалось занять локальный порт ${spec.bindHost}:${spec.bindPort}", e,
                )
            }
            SshjDynamicForward(client, serverSocket)
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        client.disconnect()
    }

    private companion object {
        const val EXEC_TIMEOUT_SECONDS = 30L
        const val KEEPALIVE_REQUEST = "keepalive@openssh.com"
        const val PING_TIMEOUT_MILLIS = 5_000L
    }
}

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
 * База для пробросов со слушателем на нашей стороне (`-L`, `-D`). Держит [serverSocket], крутит accept
 * на демоническом потоке, на каждое соединение запускает [handle] в своём потоке. Несёт паузу (на
 * паузе принятое соединение сразу рвём, порт держим) и счётчики [up]/[down], которые наполняет
 * [handle] через [tunnel]. [close] закрывает слушатель и все живые туннели.
 *
 * Поток accept НЕ запускается в конструкторе базы (иначе [handle] мог бы сработать на ещё не
 * достроенном подклассе) — подкласс вызывает [startAccepting] в конце своего init.
 */
private abstract class AcceptingForward(
    private val serverSocket: ServerSocket,
    private val threadName: String,
) : PortForward {

    protected val active = AtomicBoolean(true)
    private val paused = AtomicBoolean(false)
    protected val up = AtomicLong(0)
    protected val down = AtomicLong(0)
    protected val live: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

    final override val boundPort: Int = serverSocket.localPort
    final override val isActive: Boolean get() = active.get() && !serverSocket.isClosed
    final override val isPaused: Boolean get() = paused.get()
    final override val bytesUp: Long get() = up.get()
    final override val bytesDown: Long get() = down.get()

    private lateinit var acceptor: Thread

    protected fun startAccepting() {
        acceptor = thread(isDaemon = true, name = "$threadName-$boundPort") {
            while (active.get() && !serverSocket.isClosed) {
                // accept роняется IOException при close() — штатное завершение цикла.
                val socket = try { serverSocket.accept() } catch (e: IOException) { break }
                // На паузе порт держим, но соединение сразу рвём — туннель не поднимаем.
                if (paused.get()) { runCatching { socket.close() }; continue }
                thread(isDaemon = true, name = "$threadName-conn-$boundPort") { handle(socket) }
            }
        }
    }

    /** Обслужить принятое соединение: открыть SSH-канал к назначению и прокачать байты через [tunnel]. */
    protected abstract fun handle(socket: Socket)

    final override suspend fun pause() = withContext(Dispatchers.IO) { paused.set(true) }
    final override suspend fun resume() = withContext(Dispatchers.IO) { paused.set(false) }

    final override suspend fun close() = withContext(Dispatchers.IO) {
        if (!active.compareAndSet(true, false)) return@withContext
        runCatching { serverSocket.close() } // рвёт accept
        live.toList().forEach { runCatching { it.close() } } // снять уже поднятые туннели
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
private class SshjLocalForward(
    private val client: SSHClient,
    serverSocket: ServerSocket,
    private val destHost: String,
    private val destPort: Int,
) : AcceptingForward(serverSocket, "skerry-local-forward") {

    init { startAccepting() }

    override fun handle(socket: Socket) {
        var channel: DirectConnection? = null
        live.add(socket)
        try {
            socket.tcpNoDelay = true
            channel = client.newDirectConnection(destHost, destPort)
            val ch = channel
            live.add(ch)
            tunnel(socket, ch, up, down, "skerry-local-$boundPort")
        } catch (e: Exception) {
            // Обрыв соединения/канала — штатное завершение туннеля.
        } finally {
            channel?.let { live.remove(it); runCatching { it.close() } }
            live.remove(socket)
            runCatching { socket.close() }
        }
    }
}

/**
 * Динамический проброс (`-D`): на слушателе держим SOCKS5-сервер. Каждое соединение проводит
 * SOCKS5-хэндшейк ([Socks5]) и под запрошенный адрес открывает direct-tcpip-канал, затем
 * двунаправленно перекачивает байты со счётом трафика.
 */
private class SshjDynamicForward(
    private val client: SSHClient,
    serverSocket: ServerSocket,
) : AcceptingForward(serverSocket, "skerry-socks") {

    init { startAccepting() }

    override fun handle(socket: Socket) {
        var channel: DirectConnection? = null
        live.add(socket)
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
            live.add(ch)
            Socks5.replySuccess(output)
            tunnel(socket, ch, up, down, "skerry-socks-$boundPort")
        } catch (e: Exception) {
            // Обрыв соединения/канала — штатное завершение туннеля.
        } finally {
            channel?.let { live.remove(it); runCatching { it.close() } }
            live.remove(socket)
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
private class SshjRemoteForward private constructor(
    private val forwarder: RemotePortForwarder,
    private val destHost: String,
    private val destPort: Int,
) : PortForward {

    private val active = AtomicBoolean(true)
    private val paused = AtomicBoolean(false)
    private val up = AtomicLong(0)
    private val down = AtomicLong(0)
    private val live: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()
    private lateinit var forward: RemotePortForwarder.Forward

    override var boundPort: Int = 0
        private set

    override val isActive: Boolean get() = active.get()
    override val isPaused: Boolean get() = paused.get()
    override val bytesUp: Long get() = up.get()
    override val bytesDown: Long get() = down.get()

    private fun gotConnect(channel: Channel.Forwarded) {
        // На паузе/после снятия отвергаем входящий канал (сервер увидит отказ), а не молча закрываем.
        if (!active.get() || paused.get()) {
            runCatching { channel.reject(OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED, "tunnel paused") }
            return
        }
        thread(isDaemon = true, name = "skerry-remote-conn-$boundPort") { handle(channel) }
    }

    private fun handle(channel: Channel.Forwarded) {
        live.add(channel)
        // Сначала соединяемся с локальным назначением; не вышло — отвергаем канал и выходим.
        val socket = try {
            Socket(destHost, destPort).apply { tcpNoDelay = true }
        } catch (e: IOException) {
            runCatching { channel.reject(OpenFailException.Reason.CONNECT_FAILED, "destination unreachable") }
            live.remove(channel)
            runCatching { channel.close() }
            return
        }
        live.add(socket)
        try {
            // Подтверждаем открытие forwarded-канала — без этого сервер не начнёт слать данные.
            channel.confirm()
            // near = локальный сокет назначения, far = канал от сервера: up — ответ назначения в канал
            // (к серверу), down — данные удалённого клиента из канала к назначению.
            tunnel(socket, channel, up, down, "skerry-remote-$boundPort")
        } catch (e: Exception) {
            // Обрыв соединения/канала — штатное завершение туннеля.
        } finally {
            live.remove(socket)
            runCatching { socket.close() }
            live.remove(channel)
            runCatching { channel.close() }
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) { paused.set(true) }
    override suspend fun resume() = withContext(Dispatchers.IO) { paused.set(false) }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!active.compareAndSet(true, false)) return@withContext
        runCatching { forwarder.cancel(forward) }
        live.toList().forEach { runCatching { it.close() } }
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

private class SshjShellChannel(
    private val session: Session,
    private val shell: Session.Shell,
) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // Счётчики трафика канала (для индикатора скорости): пишутся из IO-потоков чтения/записи,
    // читаются из поллера на другой корутине — AtomicLong для потокобезопасной видимости.
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    override val bytesUp: Long get() = _bytesUp.get()
    override val bytesDown: Long get() = _bytesDown.get()

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
            if (read > 0) {
                _bytesDown.addAndGet(read.toLong())
                emit(buffer.copyOf(read))
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            shell.outputStream.write(data)
            shell.outputStream.flush()
            _bytesUp.addAndGet(data.size.toLong())
            Unit
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

/**
 * [KeyProvider] для аутентификации по сертификату: приватный ключ берётся из уже загруженного
 * [privateKeys] (PEM), а публичная часть — распарсенный из строки [certificate] объект `Certificate`
 * (sshj-декодер `Buffer.readPublicKey` для cert-типа возвращает именно его). Тип берём из первого
 * поля строки (`ssh-…-cert-v01@openssh.com`) — это `*_CERT`, по нему sshj и шлёт серверу cert-blob.
 */
private fun certificateKeyProvider(privateKeys: KeyProvider, certificate: String): KeyProvider {
    val fields = certificate.trim().split(Regex("\\s+"))
    // Битая/обрезанная строка cert (нет второго поля, невалидный base64, мусор в wire-данных) не
    // должна вылетать необработанным IndexOutOfBounds/IllegalArgument мимо обработчиков auth —
    // конвертируем в SshAuthenticationException (предъявить учётные данные не удалось).
    val (certType, certKey) = runCatching {
        require(fields.size >= 2) { "ожидался формат '<type> <base64> [comment]'" }
        KeyType.fromString(fields[0]) to Buffer.PlainBuffer(Base64.getDecoder().decode(fields[1])).readPublicKey()
    }.getOrElse { throw SshAuthenticationException("Сохранённый SSH-сертификат не удалось разобрать", it) }
    return object : KeyProvider {
        override fun getPrivate(): PrivateKey = privateKeys.private
        override fun getPublic(): PublicKey = certKey
        override fun getType(): KeyType = certType
    }
}

/** Fingerprint в формате OpenSSH: `SHA256:` + base64 без паддинга от wire-кодировки ключа. */
private fun opensshFingerprint(key: PublicKey): String {
    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
