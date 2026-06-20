package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"

private val acceptAllKeys = HostKeyVerifier { _, _, _, _ -> true }

/**
 * Интеграционные тесты проброса портов против встроенного Apache MINA SSHD. Назначение туннеля —
 * локальный однопоточный echo-сервер, поднятый в этом же процессе; форвардинг сервер пропускает
 * через [AcceptAllForwardingFilter].
 */
class SshjPortForwardTest {

    private lateinit var server: SshServer

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
    }

    private suspend fun connect(): SshConnection =
        SshjTransport(acceptAllKeys).connect(
            SshTarget(host = "127.0.0.1", port = server.port, username = USER),
            SshAuth.Password(PASSWORD),
        )

    @Test
    fun `local forward tunnels bytes to the destination through the server`() = runTest {
        EchoServer().use { echo ->
            val connection = connect()
            try {
                val forward = connection.forwardLocal(
                    LocalForwardSpec(bindPort = 0, destHost = "127.0.0.1", destPort = echo.port),
                )
                try {
                    assertTrue(forward.isActive)
                    assertTrue(forward.boundPort > 0, "слушатель должен получить порт от ОС")
                    assertEquals("ping", roundTrip("127.0.0.1", forward.boundPort, "ping"))
                } finally {
                    forward.close()
                }
                assertFalse(forward.isActive)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun `remote forward tunnels bytes back to a local destination`() = runTest {
        EchoServer().use { echo ->
            val connection = connect()
            try {
                val forward = connection.forwardRemote(
                    RemoteForwardSpec(bindPort = 0, destHost = "127.0.0.1", destPort = echo.port),
                )
                try {
                    assertTrue(forward.boundPort > 0, "сервер должен назначить порт")
                    // Слушатель на стороне сервера (127.0.0.1 — тот же хост в тесте); коннект к нему
                    // туннелируется обратно к нашему echo.
                    assertEquals("pong", roundTrip("127.0.0.1", forward.boundPort, "pong"))
                } finally {
                    forward.close()
                }
                assertFalse(forward.isActive)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun `dynamic forward proxies via SOCKS5 to the destination through the server`() = runTest {
        EchoServer().use { echo ->
            val connection = connect()
            try {
                val forward = connection.forwardDynamic(DynamicForwardSpec(bindPort = 0))
                try {
                    assertTrue(forward.isActive)
                    assertTrue(forward.boundPort > 0, "SOCKS-слушатель должен получить порт от ОС")
                    // Клиент SOCKS5 сам сообщает адрес назначения (наш echo), туннель идёт через сервер.
                    assertEquals("socks", socksRoundTrip(forward.boundPort, "127.0.0.1", echo.port, "socks"))
                } finally {
                    forward.close()
                }
                assertFalse(forward.isActive)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun `local forward rejects an already occupied bind port`() = runTest {
        ServerSocket().use { occupied ->
            occupied.bind(InetSocketAddress("127.0.0.1", 0))
            val connection = connect()
            try {
                assertFailsWith<PortForwardException> {
                    connection.forwardLocal(
                        LocalForwardSpec(
                            bindPort = occupied.localPort,
                            destHost = "127.0.0.1",
                            destPort = occupied.localPort,
                        ),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** Отправляет [message] на [host]:[port], возвращает эхо-ответ. */
private fun roundTrip(host: String, port: Int, message: String): String =
    Socket(host, port).use { socket ->
        socket.getOutputStream().apply {
            write(message.encodeToByteArray())
            flush()
        }
        val buffer = ByteArray(message.length)
        var read = 0
        while (read < buffer.size) {
            val n = socket.getInputStream().read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read).decodeToString()
    }

/**
 * Прокидывает [message] на [targetHost]:[targetPort] через локальный SOCKS5-прокси на
 * 127.0.0.1:[proxyPort] и возвращает эхо-ответ. Реализует клиентскую сторону SOCKS5 (no-auth +
 * CONNECT, IPv4) — встречно к серверной части в [Socks5].
 */
private fun socksRoundTrip(proxyPort: Int, targetHost: String, targetPort: Int, message: String): String =
    Socket("127.0.0.1", proxyPort).use { socket ->
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        // Приветствие: VER=5, NMETHODS=1, METHOD=0(no-auth). Сервер должен выбрать no-auth.
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val method = readFully(input, 2)
        require(method[0].toInt() == 5 && method[1].toInt() == 0) { "SOCKS5: сервер не выбрал no-auth" }
        // Запрос: CONNECT(1), ATYP=IPv4(1), адрес, порт (big-endian).
        val addr = targetHost.split(".").map { it.toInt().toByte() }.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 1) + addr + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
        out.flush()
        val reply = readFully(input, 10)
        require(reply[1].toInt() == 0) { "SOCKS5: CONNECT отклонён, REP=${reply[1]}" }
        // Туннель установлен — гоняем эхо.
        out.write(message.encodeToByteArray()); out.flush()
        val buffer = ByteArray(message.length)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read).decodeToString()
    }

private fun readFully(input: java.io.InputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = input.read(buf, off, n - off)
        if (r < 0) break
        off += r
    }
    return buf
}

/** Однопоточный echo-сервер на свободном loopback-порту; читает запрос и шлёт его обратно. */
private class EchoServer : Closeable {
    private val socket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int get() = socket.localPort

    private val acceptor = thread(isDaemon = true, name = "echo-acceptor") {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            thread(isDaemon = true, name = "echo-conn") {
                client.use {
                    // Потоковое эхо: возвращаем каждый прочитанный кусок сразу, не дожидаясь EOF —
                    // иначе клиент, держащий сокет открытым в ожидании ответа, и сервер, читающий до
                    // EOF, заклинивают друг друга.
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = try {
                            input.read(buffer)
                        } catch (_: Exception) {
                            break
                        }
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        output.flush()
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
        acceptor.join(1000)
    }
}
