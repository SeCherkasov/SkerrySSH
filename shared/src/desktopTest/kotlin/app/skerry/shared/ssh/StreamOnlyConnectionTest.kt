package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Base tests for [StreamOnlyConnection]: every SSH capability missing from stream-only protocols
 * (exec, SFTP, forwarding) throws [UnsupportedOperationException] with the protocol name in the message.
 */
class StreamOnlyConnectionTest {

    private class TestConnection : StreamOnlyConnection("TestProto") {
        override val isConnected: Boolean get() = false
        override suspend fun openShell(size: PtySize, term: String): ShellChannel =
            throw UnsupportedOperationException("not needed in the test")
        override suspend fun disconnect() = Unit
    }

    private fun assertUnsupported(block: suspend () -> Unit) {
        val e = assertFailsWith<UnsupportedOperationException> { runBlocking { block() } }
        assertTrue(e.message!!.startsWith("TestProto does not support"), "message should carry the protocol name: ${e.message}")
    }

    @Test
    fun `exec is unsupported`() = assertUnsupported { TestConnection().exec("ls") }

    @Test
    fun `sftp is unsupported`() = assertUnsupported { TestConnection().openSftp() }

    @Test
    fun `local forward is unsupported`() = assertUnsupported {
        TestConnection().forwardLocal(LocalForwardSpec(bindHost = "127.0.0.1", bindPort = 0, destHost = "h", destPort = 80))
    }

    @Test
    fun `remote forward is unsupported`() = assertUnsupported {
        TestConnection().forwardRemote(RemoteForwardSpec(bindHost = "127.0.0.1", bindPort = 0, destHost = "h", destPort = 80))
    }

    @Test
    fun `dynamic forward is unsupported`() = assertUnsupported {
        TestConnection().forwardDynamic(DynamicForwardSpec(bindHost = "127.0.0.1", bindPort = 0))
    }
}
