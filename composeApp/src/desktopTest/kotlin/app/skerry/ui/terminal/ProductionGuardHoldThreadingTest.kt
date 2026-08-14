package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.guard.ProductionGuardPolicy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two inputs reach the guard at once: a runbook step advances on its own dispatcher while the user
 * presses Enter on the same pane. Exactly one may be held, and the block replayed has to be the one
 * the dialog was published for — confirming a question about A must never run B.
 *
 * On the JVM because it takes real threads; the class under test is common.
 */
class ProductionGuardHoldThreadingTest {

    @Test
    fun `only one input is held, and it is the one the dialog was published for`() {
        repeat(ROUNDS) {
            val hold = ProductionGuardHold().apply {
                policy = ProductionGuardPolicy(production = true, confirmWarnings = true)
            }
            val start = CountDownLatch(1)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())

            fun holder(command: String) = thread(start = false) {
                start.await()
                hold.hold("$command\n", HeldInputSource.Paste, quote = { command }) { policy -> ProductionGuard.inspect(listOf(command), policy) }
            }.apply {
                setUncaughtExceptionHandler { _, t -> failures += t }
                start()
            }

            val typed = holder("rm -rf /srv/typed")
            val step = holder("rm -rf /srv/step")
            start.countDown()
            listOf(typed, step).forEach { it.join() }

            assertNull(failures.firstOrNull(), "holding threw: ${failures.firstOrNull()}")
            val quote = hold.pendingQuote
            assertTrue(quote.isNotEmpty(), "something was held with nothing published for it")
            assertEquals("$quote\n", hold.take()?.text, "the dialog asked about one block and another was replayed")
        }
    }
}

/** Enough rounds to hit the interleaving; the whole test runs in well under a second. */
private const val ROUNDS = 2_000
