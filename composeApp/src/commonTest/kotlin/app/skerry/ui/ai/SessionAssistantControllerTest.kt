package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiRoute
import app.skerry.shared.ai.AiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingProvider(
    private val deltas: List<String> = emptyList(),
    private val failWith: AiException? = null,
    private val hang: Boolean = false,
    /**
     * Runs after the last delta, while the stream is still inside `collect` — the window where a
     * cancellation request has been made but the coroutine has not yet reached a suspension point.
     */
    private val beforeCompleting: () -> Unit = {},
    /** Runs between the first delta and the rest — the window a mid-stream cancel lands in. */
    private val afterFirstDelta: () -> Unit = {},
) : AiProvider {
    var built = false
    var lastMessages: List<AiMessage> = emptyList()
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        built = true
        lastMessages = request.messages
        failWith?.let { throw it }
        deltas.forEachIndexed { index, delta ->
            emit(AiDelta(delta))
            if (index == 0) afterFirstDelta()
        }
        beforeCompleting()
        if (hang) kotlinx.coroutines.awaitCancellation()
    }
    override suspend fun close() {}
}

/**
 * Session assistant: a conversation about the open session, under the host's [AiPolicy]. Commands it
 * proposes are never executed by the controller — the panel sends them only on an explicit click.
 */
class SessionAssistantControllerTest {

    private fun controller(
        policy: AiPolicy,
        settings: AiSettings,
        provider: AiProvider,
        scope: CoroutineScope,
    ) = SessionAssistantController(
        policy,
        settings = { settings },
        providerFactory = { provider },
        scope = scope,
    )

    @Test
    fun `off policy sends nothing and records no turn`() = runTest {
        val p = RecordingProvider(deltas = listOf("hi"))
        val c = controller(AiPolicy.Off, AiSettings(apiKey = "sk-x"), p, this)

        assertFalse(c.aiEnabled)
        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertFalse(p.built)
        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `strict policy without a local model blocks before building a provider`() = runTest {
        val p = RecordingProvider(deltas = listOf("hi"))
        val c = controller(AiPolicy.Strict, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE), c.notice)
        assertFalse(p.built)
        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `globally disabled provider blocks even under a permissive policy`() = runTest {
        val p = RecordingProvider(deltas = listOf("hi"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x", provider = AiProviderKind.OFF), p, this)

        c.ask("hello", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.Blocked(AiRoute.Reason.AI_DISABLED), c.notice)
        assertFalse(p.built)
    }

    /**
     * The conversation is replayed in full on every question, and only the attached terminal output
     * was ever bounded. A long session therefore grows past what the endpoint accepts: the local
     * model (4096 tokens on desktop, 2048 on a phone) silently drops the front of the prompt and
     * the answers quietly degrade, and a cloud endpoint answers 400 for every further question.
     */
    @Test
    fun `a long conversation is replayed within a bounded budget`() = runTest {
        val provider = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x"), provider, this)
        val long = "x".repeat(2_000)

        repeat(20) {
            c.ask("$long question $it", outputs = emptyList())
            advanceUntilIdle()
        }

        val replayed = provider.lastMessages.filter { it.role != AiRole.SYSTEM }.sumOf { it.content.length }
        assertTrue(
            replayed <= AI_HISTORY_LIMIT + long.length,
            "the whole conversation was replayed: $replayed characters",
        )
        // The newest exchange is what survives the trim — the question just asked is in there.
        assertTrue(provider.lastMessages.last().content.contains("question 19"))
    }

    @Test
    fun `a full round trip appends the user turn and the reply`() = runTest {
        val p = RecordingProvider(deltas = listOf("The journal", " is the largest."))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(2, c.turns.size)
        assertEquals(AiRole.USER, c.turns[0].role)
        assertEquals("what is eating the disk?", c.turns[0].text)
        assertEquals(AiRole.ASSISTANT, c.turns[1].role)
        assertEquals("The journal is the largest.", c.turns[1].text)
        assertFalse(c.busy)
        assertNull(c.streaming)
        assertNull(c.notice)
    }

    @Test
    fun `the request carries a system prompt and the prior conversation`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("first question", outputs = emptyList())
        advanceUntilIdle()
        c.ask("and the docker layers?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiRole.SYSTEM, p.lastMessages.first().role)
        assertEquals(
            listOf("first question", "ok", "and the docker layers?"),
            p.lastMessages.drop(1).map { it.content },
        )
    }

    @Test
    fun `the counter decides how many recent outputs are attached`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)
        val outputs = listOf("# df -h\n42G", "# free -h\n7.8Gi", "# uptime\nload 0.42")

        c.selectContextOutputs(2)
        c.ask("what is eating the disk?", outputs = outputs)
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertTrue(sent.contains("free -h"), sent)
        assertTrue(sent.contains("uptime"), sent)
        assertFalse(sent.contains("df -h"), sent)
        assertTrue(sent.contains("what is eating the disk?"), sent)
        // What was attached is visible in the feed, so the user sees what left the machine.
        assertEquals(2, c.turns[0].outputs)
    }

    @Test
    fun `a zero counter attaches nothing`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(0)
        c.ask("hello", outputs = listOf("# df -h\n42G"))
        advanceUntilIdle()

        assertEquals("hello", p.lastMessages.last().content)
        assertEquals(0, c.turns[0].outputs)
    }

    @Test
    fun `explain attaches its output even when the context counter is off`() = runTest {
        // The Explain button is about one specific chunk the user is looking at; the counter governs
        // what rides along with a typed question, not this.
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(0)
        c.explain("Explain this output", output = "# df -h\n/dev/sda1  87% /")
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertTrue(sent.contains("87%"), sent)
        assertEquals("Explain this output", c.turns[0].text)
        assertEquals(1, c.turns[0].outputs)
    }

    @Test
    fun `explain with nothing on screen does not send a bare question`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.explain("Explain this output", output = "   ")
        advanceUntilIdle()

        assertFalse(p.built)
        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `the reply language is stated in the system prompt`() = runTest {
        // A small local model mirrors the prompt's language; the UI locale has to reach it or the
        // answer comes back in English next to a Russian interface.
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = SessionAssistantController(
            AiPolicy.Balanced,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { p },
            scope = this,
            responseLanguage = { "Russian" },
        )

        c.ask("hello", outputs = emptyList())
        advanceUntilIdle()

        assertTrue(p.lastMessages.first().content.contains("Russian"))
    }

    @Test
    fun `secrets are stripped from the prompt and from the attached output`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(1)
        // Terminal output is the risky half: it carries whatever scrolled past, including an
        // exported password. The prompt goes through the same redactor.
        c.ask("why does token=abc-secret-value fail?", outputs = listOf("# env | grep PG\nPGPASSWORD=hunter2"))
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertFalse(sent.contains("hunter2"), sent)
        assertFalse(sent.contains("abc-secret-value"), sent)
        assertTrue(sent.contains("PGPASSWORD="), "the key stays visible, only the value is masked")
        // The feed shows the redacted text, so history and display agree with what was sent.
        assertFalse(c.turns[0].text.contains("abc-secret-value"))
    }

    @Test
    fun `cancelling mid-flight clears busy and keeps the conversation`() = runTest {
        val p = RecordingProvider(deltas = listOf("partial"), hang = true)
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        runCurrent()
        assertTrue(c.busy)
        c.cancel()
        advanceUntilIdle()

        assertFalse(c.busy)
        assertNull(c.streaming)
        assertEquals(1, c.turns.size)
        assertEquals(AiRole.USER, c.turns.single().role)
    }

    @Test
    fun `a cancelled request cannot clear the state of the next one`() = runTest {
        val hanging = RecordingProvider(deltas = listOf("old"), hang = true)
        val fresh = RecordingProvider(deltas = listOf("new answer"))
        var current: AiProvider = hanging
        val c = SessionAssistantController(
            AiPolicy.Balanced,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { current },
            scope = this,
        )

        c.ask("first", outputs = emptyList())
        runCurrent()
        c.cancel()
        current = fresh
        c.ask("second", outputs = emptyList())
        advanceUntilIdle()

        assertFalse(c.busy)
        assertEquals("new answer", c.turns.last().text)
        assertEquals(AiRole.ASSISTANT, c.turns.last().role)
    }

    @Test
    fun `a provider failure becomes a notice and releases busy`() = runTest {
        val p = RecordingProvider(failWith = AiException(AiException.Kind.UNAUTHORIZED, "no"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.Error(AiFailure.UNAUTHORIZED), c.notice)
        assertFalse(c.busy)
        assertNull(c.streaming)
    }

    @Test
    fun `asking while busy is ignored`() = runTest {
        val p = RecordingProvider(deltas = listOf("partial"), hang = true)
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("first", outputs = emptyList())
        runCurrent()
        c.ask("second", outputs = emptyList())
        runCurrent()

        assertEquals(1, c.turns.size)
        c.cancel()
    }

    @Test
    fun `a reply that lands after the stop button does not enter the conversation`() = runTest {
        // cancel() only requests cancellation: a stream that already finished collecting reaches its
        // completion callback without crossing a suspension point, so the guard has to be on the
        // callback itself — otherwise the answer the user just stopped appears in the feed.
        var c: SessionAssistantController? = null
        val p = RecordingProvider(deltas = listOf("stale answer"), beforeCompleting = { c?.cancel() })
        c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(1, c.turns.size, "only the user turn survives a cancelled request")
        assertEquals(AiRole.USER, c.turns.single().role)
        assertFalse(c.busy)
    }

    @Test
    fun `a failure that lands after the stop button raises no notice`() = runTest {
        var c: SessionAssistantController? = null
        val p = RecordingProvider(
            deltas = listOf("partial"),
            failWith = null,
            beforeCompleting = { c?.cancel(); throw AiException(AiException.Kind.NETWORK, "late") },
        )
        c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertNull(c.notice, "a cancelled request must not report its failure over the next state")
    }

    @Test
    fun `a delta that lands after the stop button does not resurrect the streaming text`() = runTest {
        // The deltas keep arriving until the cancellation reaches a suspension point; an unguarded
        // onDelta would put the stopped answer back on screen after the panel cleared it.
        var c: SessionAssistantController? = null
        val p = RecordingProvider(
            deltas = listOf("first", " second"),
            afterFirstDelta = { c?.cancel() },
        )
        c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertNull(c.streaming, "a cancelled request must not keep writing into the panel")
        assertFalse(c.busy)
    }

    @Test
    fun `an empty reply says the model answered nothing, not that it was not a command`() = runTest {
        // Rejected is the one-shot bar's "that is not a command"; here the question was free-form
        // prose and the model simply returned nothing.
        val p = RecordingProvider(deltas = listOf("   "))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.NoAnswer, c.notice)
        assertEquals(1, c.turns.size)
        assertFalse(c.busy)
    }

    @Test
    fun `a permissive policy sends the question unredacted`() = runTest {
        // Permissive is the documented "non-sensitive systems only" escape hatch: the redactor is
        // deliberately off there, and the false branch has to stay reachable.
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(1)
        c.ask("why does token=abc-secret-value fail?", outputs = listOf("PGPASSWORD=hunter2"))
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertTrue(sent.contains("abc-secret-value"), sent)
        assertTrue(sent.contains("hunter2"), sent)
    }

    @Test
    fun `a downloaded local model answers under the strict policy`() = runTest {
        // Strict routes to the device endpoint; without this the panel is blocked on every host that
        // opted out of the cloud, model downloaded or not.
        val p = RecordingProvider(deltas = listOf("local answer"))
        val c = SessionAssistantController(
            AiPolicy.Strict,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { p },
            scope = this,
            localInstalled = { true },
        )

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertNull(c.notice)
        assertTrue(p.built)
        assertEquals("local answer", c.turns.last().text)
    }

    @Test
    fun `clear drops the conversation and cancels the request`() = runTest {
        val p = RecordingProvider(deltas = listOf("partial"), hang = true)
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        runCurrent()
        c.clear()
        advanceUntilIdle()

        assertTrue(c.turns.isEmpty())
        assertFalse(c.busy)
        assertNull(c.notice)
    }
}
