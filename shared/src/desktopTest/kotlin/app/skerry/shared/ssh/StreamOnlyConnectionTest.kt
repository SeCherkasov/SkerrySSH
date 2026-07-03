package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Тесты базы [StreamOnlyConnection]: все возможности SSH, отсутствующие у потоковых протоколов
 * (exec, SFTP, пробросы), бросают [UnsupportedOperationException] с именем протокола в сообщении.
 */
class StreamOnlyConnectionTest {

    private class TestConnection : StreamOnlyConnection("Проток") {
        override val isConnected: Boolean get() = false
        override suspend fun openShell(size: PtySize, term: String): ShellChannel =
            throw UnsupportedOperationException("не нужен в тесте")
        override suspend fun disconnect() = Unit
    }

    private fun assertUnsupported(block: suspend () -> Unit) {
        val e = assertFailsWith<UnsupportedOperationException> { runBlocking { block() } }
        assertTrue(e.message!!.startsWith("Проток не поддерживает"), "сообщение должно нести имя протокола: ${e.message}")
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
