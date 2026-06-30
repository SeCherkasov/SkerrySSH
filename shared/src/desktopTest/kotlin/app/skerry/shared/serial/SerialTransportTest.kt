package app.skerry.shared.serial

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_MS = 15_000L

/**
 * Тесты Serial-транспорта через подставной [SerialPortHandle] (пайпы в памяти) — без реального
 * железа. Проверяют проброс байтов в обе стороны, обработку недоступности порта и завершение
 * [output] при закрытии.
 */
class SerialTransportTest {

    /** Fake-порт: чтение из пайпа (в него пишет «устройство»), запись копится в буфер. */
    private class FakeHandle : SerialPortHandle {
        val deviceToApp = PipedOutputStream()
        private val appReads = PipedInputStream(deviceToApp)
        val written = ByteArrayOutputStream()
        private var open = true
        override val isOpen: Boolean get() = open
        override fun read(buffer: ByteArray): Int = appReads.read(buffer)
        override fun write(data: ByteArray) { written.write(data); written.flush() }
        override fun close() {
            open = false
            runCatching { deviceToApp.close() }
            runCatching { appReads.close() }
        }
    }

    private fun transport(handle: SerialPortHandle) = SerialTransport(openPort = { handle })

    @Test
    fun `bytes written to the channel reach the port`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(SshTarget(host = "/dev/ttyUSB0", port = 9600, username = ""), SshAuth.Password(""))
        val shell = conn.openShell(PtySize())
        withTimeout(TIMEOUT_MS) { shell.write("AT\r".encodeToByteArray()) }
        assertEquals("AT\r", handle.written.toByteArray().decodeToString())
        conn.disconnect()
    }

    @Test
    fun `bytes from the port reach the terminal output`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(SshTarget(host = "/dev/ttyUSB0", port = 115200, username = ""), SshAuth.Password(""))
        val shell = conn.openShell(PtySize())
        withTimeout(TIMEOUT_MS) {
            handle.deviceToApp.write("OK\r\n".encodeToByteArray())
            handle.deviceToApp.flush()
            val received = StringBuilder()
            shell.output.first { chunk ->
                received.append(chunk.decodeToString())
                received.contains("OK\r\n")
            }
        }
        conn.disconnect()
    }

    @Test
    fun `output completes when the port closes`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(SshTarget(host = "/dev/ttyUSB0", port = 9600, username = ""), SshAuth.Password(""))
        val shell = conn.openShell(PtySize())
        withTimeout(TIMEOUT_MS) {
            handle.close()
            shell.output.collect { }
        }
        assertTrue(true) // дошли сюда — flow завершился, а не завис
        conn.disconnect()
    }

    @Test
    fun `unavailable port surfaces as a connection exception`() = runBlocking<Unit> {
        val transport = SerialTransport(openPort = { throw SerialUnavailableException("нет порта") })
        assertFailsWith<SshConnectionException> {
            transport.connect(SshTarget(host = "/dev/ttyZZZ", port = 9600, username = ""), SshAuth.Password(""))
        }
    }
}
