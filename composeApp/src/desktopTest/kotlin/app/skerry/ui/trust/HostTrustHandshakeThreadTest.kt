package app.skerry.ui.trust

import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.shared.trust.asDecider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun request(host: String) = HostTrustRequest(
    kind = HostTrustKind.SshHostKey,
    host = host,
    port = 22,
    keyType = "ssh-ed25519",
    fingerprint = "SHA256:$host",
)

/**
 * The controller as the connection actually reaches it: `decide` blocks a transport thread while the
 * answer comes from another one. Every other test drives it on a single test dispatcher, where the
 * mutex, the flow and the deferred are never contended and a scheduler decides the order.
 */
class HostTrustHandshakeThreadTest {

    private fun awaitPending(controller: HostTrustPromptController, id: Long): HostTrustQuestion {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val showing = controller.pending.value
            if (showing != null && showing.id == id) return showing
            Thread.sleep(1)
        }
        error("question $id never reached the screen")
    }

    @Test
    fun `an answer from the UI thread reaches a decision blocked on another thread`() {
        val controller = HostTrustPromptController()
        val decider = controller.asDecider()
        var trusted = false
        val handshake = thread { trusted = decider.decide(request("desk")) }

        val question = awaitPending(controller, id = 0)
        controller.accept(question.id)
        handshake.join(5_000)

        assertFalse(handshake.isAlive, "the handshake thread was left blocked on an answered question")
        assertTrue(trusted, "the key the user accepted was not trusted")
    }

    @Test
    fun `queued questions each get their own answer, and neither takes the other's`() {
        // Two hosts dialled together. The second one waits its turn on a thread of its own, and the
        // answer given to the first must not settle it — the ids and the deferred behind them are
        // read across three threads here, which is the shape the single-dispatcher tests cannot make.
        val controller = HostTrustPromptController()
        val decider = controller.asDecider()
        val answers = mutableMapOf<String, Boolean>()
        val bothAsked = CountDownLatch(2)
        val threads = listOf("first", "second").map { host ->
            thread {
                bothAsked.countDown()
                val trusted = decider.decide(request(host))
                synchronized(answers) { answers[host] = trusted }
            }
        }
        bothAsked.await(5, TimeUnit.SECONDS)

        // Whichever took the turnstile first is question 0; it is refused, then the other is accepted.
        val first = awaitPending(controller, id = 0)
        controller.refuse(first.id)
        val second = awaitPending(controller, id = 1)
        controller.accept(second.id)
        threads.forEach { it.join(5_000) }

        val firstHost = first.request.host
        val secondHost = second.request.host
        assertEquals(
            mapOf(firstHost to false, secondHost to true),
            synchronized(answers) { answers.toMap() },
            "an answer landed on the question it was not given for",
        )
    }

    @Test
    fun `an answer for a question already gone is dropped rather than applied to the next`() {
        val controller = HostTrustPromptController()
        val decider = controller.asDecider()
        var first = true
        val firstThread = thread { first = decider.decide(request("first")) }
        val stale = awaitPending(controller, id = 0)
        controller.refuse(stale.id)
        firstThread.join(5_000)
        assertFalse(first)

        var second = false
        val secondThread = thread { second = decider.decide(request("second")) }
        awaitPending(controller, id = 1)
        controller.accept(stale.id)
        secondThread.join(200)
        assertTrue(secondThread.isAlive, "a stale id answered the question that replaced it")

        controller.accept(1)
        secondThread.join(5_000)
        assertTrue(second)
    }
}
