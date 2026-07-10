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
import kotlinx.coroutines.test.runCurrent
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
    var lastTemperature: Double? = null
    var built = false
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        built = true
        lastUserContent = request.messages.last().content
        lastTemperature = request.temperature
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
    fun `globally disabled provider blocks even under permissive policy`() = runTest {
        // The bar is hidden at the view level when globally OFF; this guards against sending if
        // the controller outlives that (settings changed while the terminal was open).
        val p = CapturingProvider(deltas = listOf("ls"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x", provider = app.skerry.shared.ai.AiProviderKind.OFF), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals(app.skerry.shared.ai.AiRoute.Reason.AI_DISABLED, c.blocked)
        assertFalse(p.built)
        assertNull(c.pending)
    }

    @Test
    fun `strict policy blocks cloud without building a provider`() = runTest {
        val p = CapturingProvider(deltas = listOf("ls"))
        val c = controller(AiPolicy.Strict, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals(app.skerry.shared.ai.AiRoute.Reason.STRICT_NEEDS_DEVICE, c.blocked)
        assertFalse(p.built)
        assertNull(c.pending)
    }

    @Test
    fun `balanced blocks when not configured`() = runTest {
        val p = CapturingProvider(deltas = listOf("ls"))
        val c = controller(AiPolicy.Balanced, AiSettings(), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals(app.skerry.shared.ai.AiRoute.Reason.CLOUD_NOT_CONFIGURED, c.blocked)
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
        // Model output can be multiline/CRLF; pending must always collapse to a single line with no line breaks.
        val p = CapturingProvider(deltas = listOf("ls -la\nrm -rf /\r\n"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals("ls -la", c.pending)
        assertFalse(c.pending!!.contains('\n'), "inserted command must never carry a newline")
        assertFalse(c.pending!!.contains('\r'))
    }

    @Test
    fun `parses the CMD and INFO reply format`() = runTest {
        val p = CapturingProvider(deltas = listOf("CMD: ls -la\n", "INFO: lists files in long format"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("list files")
        advanceUntilIdle()

        assertEquals("ls -la", c.pending)
        assertEquals("lists files in long format", c.pendingInfo)
    }

    @Test
    fun `an ASK clarification is rejected, not offered as a runnable command`() = runTest {
        // The bar only runs commands; a clarification is shown as a fixed "not a command" message
        // (the UI localizes it), never as the model's own text.
        val p = CapturingProvider(deltas = listOf("ASK: Which directory should I search?"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("find the thing")
        advanceUntilIdle()

        assertNull(c.pending)
        assertTrue(c.rejected)
        assertNull(c.error)
    }

    @Test
    fun `a prose reply without a marker is rejected, not offered as a command`() = runTest {
        // Greetings and small talk produce off-topic prose; it must be rejected, never run or echoed.
        val p = CapturingProvider(deltas = listOf("Приветствую! Какой у вас вопрос?"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("приветствую")
        advanceUntilIdle()

        assertNull(c.pending)
        assertTrue(c.rejected)
    }

    @Test
    fun `strips wrapping backticks the model sometimes adds`() = runTest {
        // Regression: bash treats backticks as command substitution, so wrapping backticks must be stripped.
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
    fun `treats a hash-prefixed refusal as rejected not a runnable command`() = runTest {
        val p = CapturingProvider(deltas = listOf("# I cannot do that safely"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("wipe the disk")
        advanceUntilIdle()

        assertNull(c.pending)
        assertTrue(c.rejected)
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
    fun `a cancelled request does not clobber the state of the next one`() = runTest {
        // Regression (job reassignment): cancel() resets busy synchronously so ask() can start a
        // new request immediately; the cancelled coroutine's finally must not clear the new request's state.
        val gateFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateSecond = kotlinx.coroutines.CompletableDeferred<Unit>()
        var call = 0
        val provider = object : AiProvider {
            override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
                if (call++ == 0) { emit(AiDelta("first")); gateFirst.await() } else { emit(AiDelta("uptime")); gateSecond.await() }
            }
            override suspend fun close() {}
        }
        val c = TerminalAiController(
            AiPolicy.Balanced, settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { provider }, scope = this,
        )

        c.ask("first request")
        runCurrent()
        assertTrue(c.busy)

        c.cancel()
        c.ask("second request")
        runCurrent()
        // The second generation is active; the cancelled first request's late finally must not clear it.
        assertTrue(c.busy, "the second request is still running")
        assertEquals("uptime", c.streaming)

        gateSecond.complete(Unit)
        advanceUntilIdle()
        assertEquals("uptime", c.pending)
        assertFalse(c.busy)
        assertNull(c.streaming)
    }

    @Test
    fun `asks for a command at a low deterministic temperature`() = runTest {
        // Regression: at the default temperature of 0.7, small local models produced random CMD/ASK output.
        val p = CapturingProvider(deltas = listOf("uptime"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("server load")
        advanceUntilIdle()

        assertEquals(TerminalAiController.COMMAND_TEMPERATURE, p.lastTemperature)
    }

    @Test
    fun `strict policy uses the on-device model when it is installed`() = runTest {
        val p = CapturingProvider(deltas = listOf("uptime"))
        var endpoint: app.skerry.shared.ai.AiEndpoint? = null
        val c = TerminalAiController(
            AiPolicy.Strict,
            settings = { AiSettings(apiKey = "sk-x") }, // cloud default; Strict still routes on-device
            providerFactory = { e -> endpoint = e; p },
            scope = this,
            localInstalled = { true },
        )

        c.ask("show uptime")
        advanceUntilIdle()

        assertTrue(endpoint is app.skerry.shared.ai.AiEndpoint.Device, "Strict must route on-device, got $endpoint")
        assertEquals("uptime", c.pending)
        assertNull(c.blocked)
    }

    @Test
    fun `device default routes on-device when installed`() = runTest {
        val p = CapturingProvider(deltas = listOf("df -h"))
        var endpoint: app.skerry.shared.ai.AiEndpoint? = null
        val c = TerminalAiController(
            AiPolicy.Balanced,
            settings = { AiSettings(provider = app.skerry.shared.ai.AiProviderKind.DEVICE) },
            providerFactory = { e -> endpoint = e; p },
            scope = this,
            localInstalled = { true },
        )

        c.ask("disk usage")
        advanceUntilIdle()

        assertTrue(endpoint is app.skerry.shared.ai.AiEndpoint.Device)
        assertEquals("df -h", c.pending)
    }

    @Test
    fun `device default without a downloaded model is blocked`() = runTest {
        val p = CapturingProvider(deltas = listOf("ls"))
        val c = TerminalAiController(
            AiPolicy.Balanced,
            settings = { AiSettings(provider = app.skerry.shared.ai.AiProviderKind.DEVICE) },
            providerFactory = { p },
            scope = this,
            localInstalled = { false },
        )

        c.ask("list files")
        advanceUntilIdle()

        assertEquals(app.skerry.shared.ai.AiRoute.Reason.DEVICE_NOT_READY, c.blocked)
        assertFalse(p.built)
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
