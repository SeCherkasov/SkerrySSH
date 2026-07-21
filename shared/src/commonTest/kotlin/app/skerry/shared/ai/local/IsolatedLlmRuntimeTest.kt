package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsolatedLlmRuntimeTest {

    private val modelPath = "/models/m.gguf".toPath()
    private val request = AiChatRequest("local", listOf(AiMessage(AiRole.USER, "hi")))

    /** Scripted host: [answers] are the event lines it replies with, per generation. */
    private class FakeLink(
        private val answers: MutableList<List<LlmHostEvent>>,
        /** Mimics a host wedged in a native call: it never answers and never closes the socket. */
        private val mute: Boolean = false,
    ) : LlmHostLink {
        val sent = mutableListOf<LlmHostCommand>()
        var closed = false
        var sendFails = false
        private val pending = Channel<String?>(Channel.UNLIMITED)

        override suspend fun send(line: String) {
            if (sendFails) throw AiException(AiException.Kind.PROTOCOL, "broken pipe")
            val command = LlmHostProtocol.decodeCommand(line)
            sent += command
            if (command !is LlmHostCommand.Generate) return
            val events = answers.removeFirstOrNull() ?: listOf(LlmHostEvent.Done)
            events.forEach { pending.send(LlmHostProtocol.encode(it)) }
            if (!mute && events.none { it is LlmHostEvent.Done || it is LlmHostEvent.Failure }) {
                pending.send(null) // host died
            }
        }

        override suspend fun receive(): String? = pending.receiveCatching().getOrNull()

        override suspend fun close() {
            closed = true
            pending.close() // a closed transport releases a read that is waiting on it
        }
    }

    private class FakeLauncher(private vararg val links: FakeLink) : LlmHostLauncher {
        var launches = 0
        override suspend fun launch(): LlmHostLink = links[launches++.coerceAtMost(links.lastIndex)]
    }

    @Test
    fun `streams deltas from the host until done`() = runTest {
        val link = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("he"), LlmHostEvent.Delta("llo"), LlmHostEvent.Done)))
        val runtime = IsolatedLlmRuntime(FakeLauncher(link))

        val deltas = runtime.generate(modelPath, request).toList()

        assertEquals(listOf(AiDelta("he"), AiDelta("llo")), deltas)
        assertEquals(LlmHostCommand.Generate(modelPath.toString(), request), link.sent.single())
    }

    @Test
    fun `the host stays alive between generations so the model is loaded once`() = runTest {
        val link = FakeLink(
            mutableListOf(
                listOf(LlmHostEvent.Delta("a"), LlmHostEvent.Done),
                listOf(LlmHostEvent.Delta("b"), LlmHostEvent.Done),
            ),
        )
        val launcher = FakeLauncher(link)
        val runtime = IsolatedLlmRuntime(launcher)

        runtime.generate(modelPath, request).toList()
        runtime.generate(modelPath, request).toList()

        assertEquals(1, launcher.launches)
    }

    @Test
    fun `a failure event keeps its kind`() = runTest {
        val link = FakeLink(
            mutableListOf(listOf(LlmHostEvent.Failure(AiException.Kind.INVALID_REQUEST, "no such model"))),
        )
        val runtime = IsolatedLlmRuntime(FakeLauncher(link))

        val error = assertFailsWith<AiException> { runtime.generate(modelPath, request).toList() }

        assertEquals(AiException.Kind.INVALID_REQUEST, error.kind)
    }

    @Test
    fun `a host that dies mid-stream surfaces an engine failure and does not kill the caller`() = runTest {
        val dying = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("half"))))
        val fresh = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("whole"), LlmHostEvent.Done)))
        val launcher = FakeLauncher(dying, fresh)
        val runtime = IsolatedLlmRuntime(launcher)

        val error = assertFailsWith<AiException> { runtime.generate(modelPath, request).toList() }
        assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
        assertTrue(dying.closed, "the dead host must be released")

        // The next request recovers on a fresh host instead of staying broken forever.
        assertEquals(listOf(AiDelta("whole")), runtime.generate(modelPath, request).toList())
        assertEquals(2, launcher.launches)
    }

    @Test
    fun `a host that died while idle is replaced without surfacing an error`() = runTest {
        val stale = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("a"), LlmHostEvent.Done)))
        val fresh = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("b"), LlmHostEvent.Done)))
        val launcher = FakeLauncher(stale, fresh)
        val runtime = IsolatedLlmRuntime(launcher)

        runtime.generate(modelPath, request).toList()
        stale.sendFails = true // the host went away between requests

        assertEquals(listOf(AiDelta("b")), runtime.generate(modelPath, request).toList())
        assertEquals(2, launcher.launches)
    }

    @Test
    fun `abandoning the stream cancels generation on the host and keeps it usable`() = runTest {
        val link = FakeLink(
            mutableListOf(
                listOf(LlmHostEvent.Delta("one"), LlmHostEvent.Delta("two"), LlmHostEvent.Done),
                listOf(LlmHostEvent.Delta("next"), LlmHostEvent.Done),
            ),
        )
        val launcher = FakeLauncher(link)
        val runtime = IsolatedLlmRuntime(launcher)

        assertEquals(AiDelta("one"), runtime.generate(modelPath, request).first()) // first() cancels the flow

        assertTrue(LlmHostCommand.Cancel in link.sent, "the host must be told to stop generating")
        assertEquals(listOf(AiDelta("next")), runtime.generate(modelPath, request).toList())
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `a host that never answers the cancel is dropped instead of blocking every later request`() = runTest {
        val wedged = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("one"))), mute = true)
        val fresh = FakeLink(mutableListOf(listOf(LlmHostEvent.Delta("next"), LlmHostEvent.Done)))
        val launcher = FakeLauncher(wedged, fresh)
        val runtime = IsolatedLlmRuntime(launcher, cancelDrainMillis = 100)

        assertEquals(AiDelta("one"), runtime.generate(modelPath, request).first()) // abandons the stream

        assertTrue(wedged.closed, "a host that ignores cancel must be torn down")
        // The runtime is usable afterwards, i.e. the lock was not left held by the cleanup.
        assertEquals(listOf(AiDelta("next")), runtime.generate(modelPath, request).toList())
        assertEquals(2, launcher.launches)
    }

    @Test
    fun `a host that dies before the request reaches it is reported and released`() = runTest {
        val stillborn = FakeLink(mutableListOf()).apply { sendFails = true }
        val launcher = FakeLauncher(stillborn)
        val runtime = IsolatedLlmRuntime(launcher)

        val error = assertFailsWith<AiException> { runtime.generate(modelPath, request).toList() }

        assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
        assertTrue(stillborn.closed, "a host that never took the request must not stay cached")
    }

    @Test
    fun `close shuts the host down`() = runTest {
        val link = FakeLink(mutableListOf(listOf(LlmHostEvent.Done)))
        val runtime = IsolatedLlmRuntime(FakeLauncher(link))
        runtime.generate(modelPath, request).toList()

        runtime.close()

        assertTrue(link.closed)
    }
}
