package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The accept loop's failure handling, independent of a live SSH server.
 *
 * `accept()` throwing is the *normal* way the loop ends — [AcceptingForward.close] closes the
 * listener to break it. What this covers is the other case: the listener is still open and the
 * forward still active, but accept failed anyway (fd exhaustion, the OS dropping the listener).
 * Breaking the loop silently there leaves a forward that reports itself alive and accepts nothing.
 */
class AcceptingForwardTest {

    /** A listener that is open by every observable measure but refuses to accept. */
    private class RefusingServerSocket : ServerSocket(0) {
        override fun accept(): Socket = throw IOException("Too many open files")
    }

    private class TestForward(
        socket: ServerSocket,
        registered: Closeable? = null,
    ) : AcceptingForward(socket, "test-forward") {
        init {
            registered?.let { state.live.add(it) }
            startAccepting()
        }
        override fun handle(socket: Socket) = Unit
    }

    /**
     * A live resource whose teardown is slow enough to be observable from another thread, and which
     * records *who* tore it down. The thread name is the whole point: asserting only that it ended
     * up closed cannot tell "close() waited for the accept loop's teardown" from "close() did the
     * teardown itself", which is exactly the ownership handoff under test.
     */
    private class SlowCloseable : Closeable {
        val closedBy = AtomicReference<String?>(null)
        override fun close() {
            Thread.sleep(TEARDOWN_MILLIS)
            closedBy.set(Thread.currentThread().name)
        }
    }

    private fun awaitAcceptorExit(forward: TestForward) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline && forward.isActive) Thread.sleep(10)
    }

    @Test
    fun `an accept failure that is not a close takes the forward down`() {
        val forward = TestForward(RefusingServerSocket())
        awaitAcceptorExit(forward)
        // Otherwise the tunnel row stays green while nothing can ever connect through it.
        assertFalse(forward.isActive, "forward reports itself active after its accept loop died")
    }

    @Test
    fun `a bound listener with a working accept stays active`() {
        val forward = TestForward(ServerSocket(0))
        assertTrue(forward.isActive)
    }

    @Test
    fun `close waits for a teardown the accept loop already started`() = runTest {
        val slow = SlowCloseable()
        val forward = TestForward(RefusingServerSocket(), registered = slow)
        // isActive drops the moment the accept loop claims the teardown — strictly before it runs
        // closeAll(), so this lands inside the window close() has to wait out.
        awaitAcceptorExit(forward)

        forward.close()

        // Two claims, and the second is the one worth having. That the resource is closed at all
        // would also hold if close() had simply done the teardown itself; that it was closed on the
        // ACCEPTOR thread is what says the loop owned the teardown — and close() still did not
        // return until that teardown finished.
        val closer = slow.closedBy.get()
        assertNotNull(closer, "close() returned while the accept loop was still tearing down")
        assertTrue(
            closer.startsWith(ACCEPTOR_THREAD_PREFIX),
            "the teardown ran on \"$closer\", so close() did it rather than waiting for the accept loop",
        )
    }

    private companion object {
        const val TEARDOWN_MILLIS = 300L

        /** [AcceptingForward] names its accept thread "<threadName>-<port>". */
        const val ACCEPTOR_THREAD_PREFIX = "test-forward-"
    }
}
