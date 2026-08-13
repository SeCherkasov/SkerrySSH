package app.skerry.shared.terminal

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine and its history are read from one thread while another writes them: a terminal
 * refreshes the ghost suggestion from the coroutine that owns the emulator, and the guard reads the
 * tracked line from whichever thread is about to send something, while the keyboard writes both.
 * That is why every field there is a value replaced whole rather than a buffer edited in place —
 * losing an update is allowed, tearing one is not.
 *
 * On the JVM because it takes real threads; the classes under test are common. A mutable history
 * list or a shared `StringBuilder` for the line fails this within a few thousand rounds.
 */
class AutocompleteThreadingTest {

    @Test
    fun `a reader never sees a torn line or a list being edited`() {
        val engine = AutocompleteEngine()
        engine.commandHistory.preload(List(50) { "systemctl restart service-$it" })
        val start = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()

        // Collected from the handler rather than a catch: what fails here is thrown by the class
        // under test, and the test says nothing about which type that is — a torn read has several.
        fun worker(name: String, body: () -> Unit) = thread(name = name, start = false) {
            start.await()
            repeat(ROUNDS) { body() }
        }.apply {
            setUncaughtExceptionHandler { _, t -> synchronized(failures) { failures += t } }
            start()
        }

        val typing = worker("typing") { engine.onUserInput("systemctl re\r".encodeToByteArray()) }
        val elsewhere = worker("elsewhere") { engine.lineRanElsewhere("systemctl r") }
        // What the terminal reads on every published snapshot and on its way to every send.
        val reading = worker("reading") {
            engine.suggestionTail()
            engine.commandHistory.commands.forEach { it.length }
            engine.commandHistory.search("service")
        }

        start.countDown()
        listOf(typing, elsewhere, reading).forEach { it.join() }
        assertNull(failures.firstOrNull(), "a reader or a writer threw: ${failures.firstOrNull()}")
        assertTrue(engine.commandHistory.commands.isNotEmpty())
    }
}

/** Enough rounds to hit an interleaving; the whole test runs in well under a second. */
private const val ROUNDS = 20_000
