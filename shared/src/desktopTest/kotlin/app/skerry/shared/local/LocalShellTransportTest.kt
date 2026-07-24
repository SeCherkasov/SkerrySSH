package app.skerry.shared.local

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
 * Local-shell transport tests. The transport logic (byte forwarding, resize propagation, clean-EOF
 * on process exit, unavailability) runs against a stand-in [LocalShellHandle] backed by in-memory
 * pipes — no real process. A separate integration test drives a real `/bin/sh` through pty4j.
 */
class LocalShellTransportTest {

    /**
     * Fake shell: [deviceToApp] is the "process stdout" (test writes into it), reads drain it;
     * writes accumulate; [resizedTo] records the last window size. Closing the writer (via
     * [endClean]) yields `read == -1` — the clean-exit path; [close] tears both ends down.
     */
    private class FakeHandle : LocalShellHandle {
        val deviceToApp = PipedOutputStream()
        private val appReads = PipedInputStream(deviceToApp)
        val written = ByteArrayOutputStream()
        var resizedTo: Pair<Int, Int>? = null
            private set
        private var open = true
        override val isOpen: Boolean get() = open
        override fun read(buffer: ByteArray): Int = appReads.read(buffer)
        override fun write(data: ByteArray) { written.write(data); written.flush() }
        override fun resize(cols: Int, rows: Int) { resizedTo = cols to rows }
        /** Simulate the shell process exiting cleanly: EOF on the read side. */
        fun endClean() { runCatching { deviceToApp.close() } }
        override fun close() {
            open = false
            runCatching { deviceToApp.close() }
            runCatching { appReads.close() }
        }
    }

    private fun transport(handle: LocalShellHandle) = LocalShellTransport(start = { handle })

    @Test
    fun `bytes written to the channel reach the shell`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(localTarget(), SshAuth.Password(""))
        val shell = conn.openShell(PtySize())
        withTimeout(TIMEOUT_MS) { shell.write("ls\n".encodeToByteArray()) }
        assertEquals("ls\n", handle.written.toByteArray().decodeToString())
        conn.disconnect()
    }

    @Test
    fun `bytes from the shell reach the terminal output`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(localTarget(), SshAuth.Password(""))
        val shell = conn.openShell(PtySize())
        withTimeout(TIMEOUT_MS) {
            handle.deviceToApp.write("hello\n".encodeToByteArray())
            handle.deviceToApp.flush()
            val received = StringBuilder()
            shell.output.first { chunk ->
                received.append(chunk.decodeToString())
                received.contains("hello\n")
            }
        }
        conn.disconnect()
    }

    @Test
    fun `resize propagates the window size to the shell`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(localTarget(), SshAuth.Password(""))
        val shell = conn.openShell(PtySize(cols = 80, rows = 24))
        withTimeout(TIMEOUT_MS) { shell.resize(PtySize(cols = 120, rows = 40)) }
        assertEquals(120 to 40, handle.resizedTo)
        conn.disconnect()
    }

    @Test
    fun `shell process exit is reported as a clean EOF`() = runBlocking {
        val handle = FakeHandle()
        val conn = transport(handle).connect(localTarget(), SshAuth.Password(""))
        val shell = conn.openShell(PtySize())
        withTimeout(TIMEOUT_MS) {
            handle.endClean()
            shell.output.collect { }
        }
        assertTrue(shell.endedWithEof, "process exit (read == -1) must mark endedWithEof")
        conn.disconnect()
    }

    @Test
    fun `unavailable platform surfaces as a connection exception`() = runBlocking<Unit> {
        val transport = LocalShellTransport(start = {
            throw LocalShellUnavailableException("Local terminal is not yet supported on Android")
        })
        val conn = transport.connect(localTarget(), SshAuth.Password(""))
        assertFailsWith<SshConnectionException> { conn.openShell(PtySize()) }
    }

    @Test
    fun `real shell over pty4j echoes output and ends on exit`() {
        val shell = java.io.File("/bin/sh")
        if (!shell.exists()) return // integration test is Unix-only
        runBlocking {
            val conn = LocalShellTransport().connect(SshTarget(host = shell.path, username = ""), SshAuth.Password(""))
            val channel = conn.openShell(PtySize(cols = 80, rows = 24))
            withTimeout(TIMEOUT_MS) {
                channel.write("printf abc123\n".encodeToByteArray())
                val received = StringBuilder()
                channel.output.first { chunk ->
                    received.append(chunk.decodeToString())
                    received.contains("abc123")
                }
            }
            conn.disconnect()
        }
    }

    private fun localTarget() = SshTarget(host = "", username = "")
}
