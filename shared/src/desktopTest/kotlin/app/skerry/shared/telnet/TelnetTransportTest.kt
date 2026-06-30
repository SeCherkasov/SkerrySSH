package app.skerry.shared.telnet

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_MS = 15_000L
private const val IAC = 255
private const val WILL = 251
private const val DO = 253
private const val ECHO = 1

/**
 * Интеграционные тесты Telnet-транспорта против сырого [ServerSocket] в этом же процессе.
 * Проверяют неготиацию опций (сервер шлёт IAC WILL ECHO → клиент отвечает IAC DO ECHO) и
 * прозрачную передачу прикладных данных (эхо-сервер).
 */
class TelnetTransportTest {

    private lateinit var server: ServerSocket
    private val clients = mutableListOf<Socket>()

    @BeforeTest
    fun start() {
        server = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
    }

    @AfterTest
    fun stop() {
        clients.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }

    /** Принять одно соединение и обработать его в фоне через [handle]. */
    private fun serve(handle: (Socket) -> Unit) {
        thread(name = "telnet-test-server", isDaemon = true) {
            runCatching {
                val s = server.accept()
                clients.add(s)
                handle(s)
            }
        }
    }

    @Test
    fun `client answers server WILL ECHO with DO ECHO`() = runBlocking {
        val negotiated = java.util.concurrent.CompletableFuture<ByteArray>()
        serve { socket ->
            socket.getOutputStream().apply { write(byteArrayOf(IAC.toByte(), WILL.toByte(), ECHO.toByte())); flush() }
            val buf = ByteArray(3)
            val n = socket.getInputStream().read(buf)
            negotiated.complete(buf.copyOf(n.coerceAtLeast(0)))
        }
        val conn = TelnetTransport().connect(SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""), SshAuth.Password(""))
        // Сбор вывода запускает цикл чтения канала: кодек обработает входящую неготиацию и отправит
        // ответ в сокет. Без активного коллектора канал не читается и обмена не будет.
        val collector = launch(Dispatchers.IO) {
            runCatching { conn.openShell(PtySize()).output.collect { } }
        }
        try {
            // Java-Future.get с таймаутом: блокирующий get() не реагирует на отмену корутины, поэтому
            // ограничиваем его сам (иначе при сбое обмена тест завис бы навсегда).
            val reply = withContext(Dispatchers.IO) {
                negotiated.get(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            assertContentEquals(byteArrayOf(IAC.toByte(), DO.toByte(), ECHO.toByte()), reply)
        } finally {
            collector.cancel()
            conn.disconnect()
        }
        Unit
    }

    @Test
    fun `application bytes flow through to the terminal`() = runBlocking {
        serve { socket ->
            // Простое эхо: копируем ввод обратно (без неготиации).
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buf = ByteArray(256)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        }
        val conn = TelnetTransport().connect(SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""), SshAuth.Password(""))
        try {
            val shell = conn.openShell(PtySize())
            withTimeout(TIMEOUT_MS) {
                shell.write("ping\n".encodeToByteArray())
                val received = StringBuilder()
                shell.output.first { chunk ->
                    received.append(chunk.decodeToString())
                    received.contains("ping\n")
                }
            }
            assertTrue(shell.isOpen)
            shell.close()
        } finally {
            conn.disconnect()
        }
        Unit
    }
}
