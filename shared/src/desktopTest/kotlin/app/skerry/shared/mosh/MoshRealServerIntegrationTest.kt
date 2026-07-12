package app.skerry.shared.mosh

import app.skerry.shared.ssh.PtySize
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end check against a REAL `mosh-server` on localhost: proves on-the-wire
 * compatibility (OCB cipher, protobuf, fragmentation, SSP) beyond our own fakes.
 * Skips silently when mosh isn't installed — install it (`sudo dnf install mosh`)
 * and rerun `:shared:desktopTest --tests "*MoshRealServer*"` to exercise it.
 */
class MoshRealServerIntegrationTest {

    private fun moshServerBinary(): String? =
        listOf("/usr/bin/mosh-server", "/usr/local/bin/mosh-server")
            .firstOrNull { File(it).canExecute() }

    @Test
    fun `full session against a real mosh-server`() {
        val binary = moshServerBinary() ?: run {
            println("mosh-server not installed — skipping the real-server integration test")
            return
        }
        // Plain launch (no -s: there is no SSH_CONNECTION here); the process daemonizes
        // after printing MOSH CONNECT, so exec-style capture works.
        val process = ProcessBuilder(binary, "new", "-c", "256", "-l", "LANG=C.UTF-8", "-l", "LC_ALL=C.UTF-8")
            .redirectErrorStream(false)
            .start()
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "mosh-server did not detach in time")
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val result = MoshBootstrap.parse(process.exitValue(), stdout, stderr)
        val success = result as? MoshBootstrapResult.Success
            ?: fail("mosh-server bootstrap failed: $result\n$stdout\n$stderr")

        runBlocking {
            val connection = MoshConnection("127.0.0.1", success.port, success.key, 10_000)
            try {
                val channel = connection.openShell(PtySize(cols = 80, rows = 24))
                val collector = launch(Dispatchers.Default) {
                    channel.output.collect { /* screen diffs; content depends on the shell */ }
                }
                // The handshake already proved two-way traffic; type an exit so the remote
                // shell terminates and mosh-server starts its shutdown exchange.
                withTimeout(10_000) {
                    channel.write("exit\r".toByteArray())
                    collector.join() // completes when the server announces shutdown
                }
                assertTrue(channel.endedWithEof, "expected a clean server-side shutdown")
            } finally {
                connection.disconnect()
            }
        }
    }
}
