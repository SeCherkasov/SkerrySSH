package app.skerry.ui.design

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Question(val id: Long, val label: String)

/**
 * The queue itself, rather than through one of its two controllers: what it does with the questions
 * waiting for a turn is not visible from either of them, and both depend on it.
 */
class PromptQueueTest {

    private fun queue(timeoutMillis: Long = 90_000) =
        PromptQueue<Question, Boolean>(timeoutMillis) { it.id }

    private suspend fun ask(queue: PromptQueue<Question, Boolean>, label: String) =
        queue.ask(build = { Question(it, label) }, onTimeout = false)

    @Test
    fun `draining answers the question waiting for a turn without ever drawing it`() = runTest {
        // The vault locks with three hosts asking. Answering only the visible one leaves the next
        // publishing itself into a chrome that is no longer composed, and its handshake holding an
        // unauthenticated socket for the whole deadline waiting for an answer nobody can give.
        //
        // What is asserted is that the queued ones were never *shown*: they refuse either way here,
        // one by the drain and one by a deadline test time skips past in an instant, and only the
        // dialog that appeared tells the two apart.
        val queue = queue()
        val shown = mutableListOf<String>()
        val watcher = launch { queue.pending.collect { question -> question?.let { shown += it.label } } }
        val first = async { ask(queue, "first") }
        yield()
        val second = async { ask(queue, "second") }
        val third = async { ask(queue, "third") }
        yield()

        queue.cancelPending(false)

        assertFalse(first.await())
        assertFalse(second.await(), "a queued question outlived the lock")
        assertFalse(third.await())
        assertEquals(listOf("first"), shown, "a question was drawn into a chrome that had gone away")
        watcher.cancel()
    }

    @Test
    fun `a question asked after the drain is drawn as usual`() = runTest {
        // Draining empties the queue; it does not close it. The user unlocks and reconnects.
        val queue = queue()
        val cancelled = async { ask(queue, "before") }
        yield()
        queue.cancelPending(false)
        assertFalse(cancelled.await())

        val asking = async { ask(queue, "after") }
        yield()

        assertEquals("after", queue.pending.value?.label)
        queue.answer(queue.pending.value?.id, true)
        assertTrue(asking.await())
    }

    @Test
    fun `a drain that lands while a question is being built refuses it there and then`() = runTest {
        // The window the generation is read a second time for: the drain ran after this ask checked
        // and before its slot was visible, so it found nothing to answer. Without the second read
        // the question goes on screen anyway — into a chrome that has gone — and its handshake is
        // held for the whole deadline. Building the question is where a test can stand inside that
        // window; on a device it is a UI thread and a transport thread.
        //
        // The return value cannot tell the two apart (both refuse), so the clock does: a question
        // left waiting spends its deadline, and here time only moves when something waits.
        val queue = queue()
        val asking = async {
            queue.ask(build = { id -> queue.cancelPending(false); Question(id, "mid-flight") }, onTimeout = false)
        }
        yield()

        assertFalse(asking.await())
        assertEquals(0L, currentTime, "a question drained mid-flight was left waiting for its deadline")
        assertNull(queue.pending.value, "a drained question was left on screen")
    }

    @Test
    fun `a drain that lands between two asks does not swallow the second`() = runTest {
        // The race the generation counter has to get right: nothing is on screen when the lock
        // arrives, and the ask that follows it entered afterwards.
        val queue = queue()
        queue.cancelPending(false)
        val asking = async { ask(queue, "after") }
        yield()

        assertEquals("after", queue.pending.value?.label)
        queue.answer(queue.pending.value?.id, true)
        assertTrue(asking.await())
    }
}
