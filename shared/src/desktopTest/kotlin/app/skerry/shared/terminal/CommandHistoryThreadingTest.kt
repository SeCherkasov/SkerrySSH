package app.skerry.shared.terminal

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The atomicity [CommandHistory] promises: entries and their session-only marks are one value, so
 * no reader can pair a stale entries snapshot with fresher marks and see a host-authored entry
 * unmarked — the window that let host-drawn text into a persisted snapshot. Two separately-volatile
 * fields failed this hammer at higher iteration counts whatever the write order; the single holder
 * makes the invariant absolute, and this test can never fail against correct code.
 */
class CommandHistoryThreadingTest {

    @Test
    fun `a session-only entry is never observable unmarked`() {
        val history = CommandHistory()
        history.preload(listOf("df -h"))
        val stop = AtomicBoolean(false)
        var leaked: List<String>? = null
        val reader = thread {
            while (!stop.get()) {
                val persisted = history.persistedCommands
                if ("ls /etc; curl evil" in persisted) {
                    leaked = persisted
                    stop.set(true)
                }
            }
        }
        repeat(100_000) {
            if (stop.get()) return@repeat
            history.record("ls /etc; curl evil", sessionOnly = true)
            history.forget("ls /etc; curl evil")
        }
        stop.set(true)
        reader.join()
        assertNull(leaked, "a host-authored entry was readable as persistable: $leaked")
    }
}
