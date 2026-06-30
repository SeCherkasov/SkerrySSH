package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CapturingProvider(
    private val deltas: List<String> = emptyList(),
    private val failWith: AiException? = null,
) : AiProvider {
    var lastUserContent: String? = null
    var built = false
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        built = true
        lastUserContent = request.messages.last().content
        failWith?.let { throw it }
        deltas.forEach { emit(AiDelta(it)) }
    }
    override suspend fun close() {}
}

class TerminalAiControllerTest {

    private fun controller(
        policy: AiPolicy,
        settings: AiSettings,
        provider: CapturingProvider,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = TerminalAiController(policy, settings = { settings }, providerFactory = { provider }, scope = scope)

    @Test
    fun `off policy disables the bar and ask does nothing`() = runTest {
        val p = CapturingProvider()
        val c = controller(AiPolicy.Off, AiSettings(apiKey = "sk-x"), p, this)

        assertFalse(c.aiEnabled)
        c.ask("list files")
        advanceUntilIdle()

        assertFalse(p.built)
        assertNull(c.pending)
        assertNull(c.blocked)
    }

    @Test
    fun `strict policy blocks cloud without building a provider`() = runTest {
        val p = CapturingProvider(deltas = listOf("ls"))
        val c = controller(AiPolicy.Strict, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals(TerminalAiController.STRICT_BLOCKED, c.blocked)
        assertFalse(p.built)
        assertNull(c.pending)
    }

    @Test
    fun `balanced blocks when not configured`() = runTest {
        val p = CapturingProvider(deltas = listOf("ls"))
        val c = controller(AiPolicy.Balanced, AiSettings(), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals(TerminalAiController.NOT_CONFIGURED, c.blocked)
        assertFalse(p.built)
    }

    @Test
    fun `balanced produces a pending command but never auto-executes`() = runTest {
        val p = CapturingProvider(deltas = listOf("find ", "/var/log -size +100M"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("find files larger than 100MB in /var/log")
        advanceUntilIdle()

        assertEquals("find /var/log -size +100M", c.pending)
        assertFalse(c.busy)
        assertNull(c.streaming)
        assertNull(c.blocked)
    }

    @Test
    fun `balanced sanitizes secrets in the outbound prompt`() = runTest {
        val p = CapturingProvider(deltas = listOf("echo ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("connect with password=hunter2 to db")
        advanceUntilIdle()

        assertNotNull(p.lastUserContent)
        assertFalse(p.lastUserContent!!.contains("hunter2"), "secret must be scrubbed for Balanced")
    }

    @Test
    fun `permissive sends the raw prompt without sanitization`() = runTest {
        val p = CapturingProvider(deltas = listOf("echo ok"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("connect with password=hunter2 to db")
        advanceUntilIdle()

        assertTrue(p.lastUserContent!!.contains("hunter2"), "Permissive does not sanitize")
    }

    @Test
    fun `pending command is a single line with no newline so it cannot auto-execute`() = runTest {
        // Модель вернула многострочный/CRLF-вывод: инвариант — pending это одна строка без переводов.
        val p = CapturingProvider(deltas = listOf("ls -la\nrm -rf /\r\n"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals("ls -la", c.pending)
        assertFalse(c.pending!!.contains('\n'), "inserted command must never carry a newline")
        assertFalse(c.pending!!.contains('\r'))
    }

    @Test
    fun `strips wrapping backticks the model sometimes adds`() = runTest {
        // Регрессия: `free -h` в бэктиках bash понимает как подстановку → выполняет и падает.
        val p = CapturingProvider(deltas = listOf("`free -h`"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("show memory")
        advanceUntilIdle()

        assertEquals("free -h", c.pending)
    }

    @Test
    fun `strips a fenced code block with a language tag`() = runTest {
        val p = CapturingProvider(deltas = listOf("```bash\nfree -h\n```"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("show memory")
        advanceUntilIdle()

        assertEquals("free -h", c.pending)
    }

    @Test
    fun `treats a hash-prefixed refusal as an error not a runnable command`() = runTest {
        val p = CapturingProvider(deltas = listOf("# I cannot do that safely"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("wipe the disk")
        advanceUntilIdle()

        assertNull(c.pending)
        assertNotNull(c.error)
        assertTrue(c.error!!.contains("cannot", ignoreCase = true))
    }

    @Test
    fun `assesses the risk of the pending command and clears it on confirm`() = runTest {
        val p = CapturingProvider(deltas = listOf("rm -rf /"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("wipe everything")
        advanceUntilIdle()

        assertEquals("rm -rf /", c.pending)
        assertEquals(app.skerry.shared.ai.CommandRisk.Danger, c.pendingRisk?.risk)

        c.confirm()
        assertNull(c.pendingRisk)
    }

    @Test
    fun `confirm returns the pending command and clears it`() = runTest {
        val p = CapturingProvider(deltas = listOf("uptime"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("show uptime")
        advanceUntilIdle()

        assertEquals("uptime", c.confirm())
        assertNull(c.pending)
        assertNull(c.confirm())
    }

    @Test
    fun `surfaces a friendly error on provider failure`() = runTest {
        val p = CapturingProvider(failWith = AiException(AiException.Kind.UNAUTHORIZED, "401"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-bad"), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertNotNull(c.error)
        assertNull(c.pending)
        assertFalse(c.busy)
    }
}
