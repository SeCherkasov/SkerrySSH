package app.skerry.shared.ssh

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"
private const val TIMEOUT_MS = 15_000L

/**
 * Интеграционные тесты shell-канала с PTY против встроенного Apache MINA SSHD.
 * Серверный shell — in-process эхо ([EchoShellFactory]): копирует stdin в stdout,
 * что даёт детерминированную проверку без внешних процессов и зависимости от ОС.
 */
class ShellChannelTest {

    private lateinit var server: SshServer

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            shellFactory = EchoShellFactory
            start()
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
    }

    private suspend fun connect(): SshConnection =
        SshjTransport { _, _, _, _ -> true }.connect(
            SshTarget(host = "127.0.0.1", port = server.port, username = USER),
            SshAuth.Password(PASSWORD),
        )

    @Test
    fun `shell echoes written bytes back`() = runBlocking {
        val connection = connect()
        try {
            val shell = connection.openShell(PtySize(cols = 80, rows = 24))
            withTimeout(TIMEOUT_MS) {
                shell.write("hello, skerry\n".encodeToByteArray())
                val received = StringBuilder()
                shell.output.first { chunk ->
                    received.append(chunk.decodeToString())
                    received.contains("hello, skerry\n")
                }
            }
            shell.close()
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `output completes and channel reports closed after close`() = runBlocking {
        val connection = connect()
        try {
            val shell = connection.openShell(PtySize())
            assertTrue(shell.isOpen)
            withTimeout(TIMEOUT_MS) {
                shell.close()
                // EOF канала должен завершить flow, а не подвесить его
                shell.output.collect { }
            }
            assertFalse(shell.isOpen)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `write after close fails with connection exception`() = runBlocking<Unit> {
        val connection = connect()
        try {
            val shell = connection.openShell(PtySize())
            shell.close()
            assertFailsWith<SshConnectionException> {
                withTimeout(TIMEOUT_MS) { shell.write("late\n".encodeToByteArray()) }
            }
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `resize while open keeps channel alive`() = runBlocking {
        val connection = connect()
        try {
            val shell = connection.openShell(PtySize(cols = 80, rows = 24))
            withTimeout(TIMEOUT_MS) {
                shell.resize(PtySize(cols = 120, rows = 40))
            }
            assertTrue(shell.isOpen)
            shell.close()
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `output rejects a second collector`() = runBlocking<Unit> {
        val connection = connect()
        try {
            val shell = connection.openShell(PtySize())
            // Закрываем заранее: первый collect доходит до EOF и завершается,
            // захватив поток; повтор обязан упасть — без гонки сборщиков.
            shell.close()
            withTimeout(TIMEOUT_MS) {
                shell.output.collect { }
                assertFailsWith<IllegalStateException> { shell.output.collect { } }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Серверный shell для тестов: эхо stdin → stdout в отдельном потоке. */
private object EchoShellFactory : ShellFactory {
    override fun createShell(channel: ChannelSession): Command = EchoCommand()
}

private class EchoCommand : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var exit: org.apache.sshd.server.ExitCallback? = null
    private var worker: Thread? = null

    override fun setInputStream(value: InputStream) { input = value }
    override fun setOutputStream(value: OutputStream) { output = value }
    override fun setErrorStream(value: OutputStream) { /* эхо пишет только в stdout */ }
    override fun setExitCallback(callback: org.apache.sshd.server.ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
        worker = thread(name = "echo-shell") {
            try {
                val buffer = ByteArray(1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
                // канал закрыт — завершаем поток
            } finally {
                exit?.onExit(0)
            }
        }
    }

    override fun destroy(channel: ChannelSession) {
        worker?.interrupt()
    }
}
