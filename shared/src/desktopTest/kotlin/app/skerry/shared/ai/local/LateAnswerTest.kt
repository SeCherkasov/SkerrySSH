package app.skerry.shared.ai.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rule this pins is the one the Android launcher's reply handler lives by, and it is plain
 * Kotlin rather than framework plumbing — which is the only reason it can be tested at all: the
 * `Messenger`/`Looper` round trip around it cannot be driven from a JVM test.
 *
 * One case implements [CompletableDeferred] by delegation, which the library asks to be opted into:
 * it is the only way to stand in the middle of the hand-over without racing two threads and hoping.
 */
@OptIn(InternalForInheritanceCoroutinesApi::class)
class LateAnswerTest {

    private class Handle {
        var closes = 0
            private set

        val closed: Boolean get() = closes > 0

        fun close() {
            closes++
        }
    }

    @Test
    fun `a reply that is waited for is handed over, not closed`() = runBlocking {
        val answer = CompletableDeferred<Handle?>()
        val held = AtomicReference<Handle?>()
        val handle = Handle()

        deliverOrClose(answer, held, handle) { it.close() }

        assertSame(handle, answer.await(), "the waiter did not receive it")
        assertFalse(handle.closed, "handed over and closed at once")
        // Left behind on purpose: the caller may still be cancelled before it takes ownership.
        assertSame(handle, held.get())
    }

    @Test
    fun `a reply nobody waits for any more is closed`() = runBlocking {
        val answer = CompletableDeferred<Handle?>()
        val held = AtomicReference<Handle?>()
        answer.complete(null) // the caller gave up and shut the door
        val late = Handle()

        deliverOrClose(answer, held, late) { it.close() }

        assertTrue(late.closed, "the late reply was dropped instead of closed")
        assertNull(held.get(), "a closed handle must not be left for the caller to close again")
        assertNull(answer.await())
    }

    @Test
    fun `an empty reply only shuts the door`() = runBlocking {
        val answer = CompletableDeferred<Handle?>()
        val held = AtomicReference<Handle?>()

        deliverOrClose(answer, held, null) { it.close() }

        assertNull(answer.await())
        assertNull(held.get())
    }

    @Test
    fun `a reply the caller reclaims mid-handover is closed once, not twice`() = runBlocking {
        val answer = CompletableDeferred<Handle?>()
        answer.complete(null) // the caller already gave up, so this delivery will lose
        val held = AtomicReference<Handle?>()
        val late = Handle()
        var taken: Handle? = null
        // Fires at the one instant that matters: the value is published and the delivery is about
        // to learn it lost. That is the window in which the launcher's teardown does
        // `opened.getAndSet(null)?.close()`, and both sides used to close the same descriptor.
        val racing = object : CompletableDeferred<Handle?> by answer {
            override fun complete(value: Handle?): Boolean {
                taken = held.getAndSet(null)?.also { it.close() }
                return answer.complete(value)
            }
        }

        deliverOrClose(racing, held, late) { it.close() }

        assertSame(late, taken, "the caller never got to reclaim it")
        assertEquals(1, late.closes, "the descriptor was closed by both sides")
    }

    @Test
    fun `only one of two replies survives, and the other is closed`() = runBlocking {
        val answer = CompletableDeferred<Handle?>()
        val held = AtomicReference<Handle?>()
        val first = Handle()
        val second = Handle()

        deliverOrClose(answer, held, first) { it.close() }
        deliverOrClose(answer, held, second) { it.close() }

        assertSame(first, answer.await())
        assertEquals(false to true, first.closed to second.closed)
        assertSame(first, held.get(), "the surviving handle was taken from the caller")
    }
}
