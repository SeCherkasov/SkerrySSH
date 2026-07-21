package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real transport is a blocking socket read, and a coroutine deadline cannot interrupt one — a
 * host that goes quiet can only be released by closing the transport. This fake reproduces that
 * property (an in-memory channel would not: its reads are cancellable), so it covers the case where
 * the cleanup after an abandoned answer would otherwise hold the runtime's lock forever and take
 * local AI down for the rest of the session.
 */
class IsolatedLlmRuntimeBlockingLinkTest {

    private val modelPath = "/models/m.gguf".toPath()
    private val request = AiChatRequest("local", listOf(AiMessage(AiRole.USER, "hi")))

    /** Answers one delta, then blocks in a way only [close] can release. */
    private class WedgedLink : LlmHostLink {
        private val released = CountDownLatch(1)
        private var answered = false
        var closed = false
            private set

        override suspend fun send(line: String) = Unit

        override suspend fun receive(): String? = withContext(Dispatchers.IO) {
            if (!answered) {
                answered = true
                LlmHostProtocol.encode(LlmHostEvent.Delta("one"))
            } else {
                released.await() // a blocking read: cancelling the coroutine does not end it
                null
            }
        }

        override suspend fun close() {
            closed = true
            released.countDown()
        }
    }

    private class HealthyLink : LlmHostLink {
        private val events = ArrayDeque(
            listOf(LlmHostEvent.Delta("next"), LlmHostEvent.Done).map(LlmHostProtocol::encode),
        )

        override suspend fun send(line: String) = Unit
        override suspend fun receive(): String? = events.removeFirstOrNull()
        override suspend fun close() = Unit
    }

    @Test
    fun `abandoning an answer on a wedged host does not lock local ai up for good`() = runBlocking {
        val wedged = WedgedLink()
        val links = ArrayDeque<LlmHostLink>(listOf(wedged, HealthyLink()))
        val runtime = IsolatedLlmRuntime(
            launcher = { links.removeFirst() },
            cancelDrainMillis = 200,
        )

        withTimeout(30.seconds) {
            assertEquals(AiDelta("one"), runtime.generate(modelPath, request).first()) // walks away

            // Before the fix this second request never returned: the cleanup was still waiting on a
            // read that no deadline could interrupt, with the runtime's lock in hand.
            assertEquals(listOf(AiDelta("next")), runtime.generate(modelPath, request).toList())
        }
        assertTrue(wedged.closed, "the wedged host must be torn down")
    }
}
