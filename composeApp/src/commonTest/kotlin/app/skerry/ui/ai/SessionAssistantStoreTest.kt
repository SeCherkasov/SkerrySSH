package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class IdleProvider : AiProvider {
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        emit(AiDelta(""))
        awaitCancellation()
    }
    override suspend fun close() {}
}

/** One conversation per pane, kept while the pane is open (see [SessionAssistantStore]). */
class SessionAssistantStoreTest {

    private fun store(scope: CoroutineScope) = SessionAssistantStore { policy ->
        SessionAssistantController(
            policy,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { IdleProvider() },
            scope = scope,
        )
    }

    @Test
    fun `the same pane keeps its conversation across lookups`() = runTest {
        val store = store(this)

        val first = store.controller("pane-1", AiPolicy.Balanced)
        val again = store.controller("pane-1", AiPolicy.Balanced)

        assertSame(first, again)
    }

    @Test
    fun `panes do not share a conversation`() = runTest {
        val store = store(this)

        assertNotSame(store.controller("pane-1", AiPolicy.Balanced), store.controller("pane-2", AiPolicy.Balanced))
    }

    @Test
    fun `a changed host policy rebuilds the controller`() = runTest {
        // The policy decides where the session's output may go; a stale controller would keep
        // routing to the cloud after the host was moved to Strict.
        val store = store(this)
        val balanced = store.controller("pane-1", AiPolicy.Balanced)
        balanced.ask("question", outputs = emptyList())
        runCurrent()
        assertTrue(balanced.busy)

        val strict = store.controller("pane-1", AiPolicy.Strict)
        runCurrent()

        assertNotSame(balanced, strict)
        assertSame(AiPolicy.Strict, strict.policy)
        assertFalse(balanced.busy, "a request routed under the old policy must not outlive it")
    }

    @Test
    fun `closing the store cancels every conversation it held`() = runTest {
        // The store is thrown away when AI is turned off globally or its provider is rebuilt; its
        // requests run on an app-scoped coroutine scope and would otherwise keep streaming.
        val store = store(this)
        val first = store.controller("pane-1", AiPolicy.Balanced)
        val second = store.controller("pane-2", AiPolicy.Balanced)
        first.ask("question", outputs = emptyList())
        second.ask("question", outputs = emptyList())
        runCurrent()
        assertTrue(first.busy && second.busy)

        store.close()
        runCurrent()

        assertFalse(first.busy)
        assertFalse(second.busy)
        assertNotSame(first, store.controller("pane-1", AiPolicy.Balanced))
    }

    @Test
    fun `closing a pane drops its conversation and cancels its request`() = runTest {
        val store = store(this)
        val controller = store.controller("pane-1", AiPolicy.Balanced)
        controller.ask("question", outputs = emptyList())
        runCurrent()
        assertTrue(controller.busy)

        store.retain(setOf("pane-2"))
        runCurrent()

        assertFalse(controller.busy, "an in-flight request of a closed pane must not keep running")
        assertNotSame(controller, store.controller("pane-1", AiPolicy.Balanced))
    }

    @Test
    fun `retain keeps the panes that are still open`() = runTest {
        val store = store(this)
        val kept = store.controller("pane-1", AiPolicy.Balanced)

        store.retain(setOf("pane-1"))

        assertSame(kept, store.controller("pane-1", AiPolicy.Balanced))
    }
}
