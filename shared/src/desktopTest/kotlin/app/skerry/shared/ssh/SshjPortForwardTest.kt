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
