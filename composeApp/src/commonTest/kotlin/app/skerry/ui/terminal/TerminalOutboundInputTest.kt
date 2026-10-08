package app.skerry.ui.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalOutboundInputTest {
    @Test
    fun `batched user input renews feedback after a slow write without reordering bytes`() = runTest {
        val outbound = TerminalOutbound()
        val publication = TerminalInputPublication { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        var callbacks = 0
        val writer = backgroundScope.launch {
            outbound.drainTo(onUserInputWrite = {
                callbacks++
                publication.onUserInputWrite()
            }) {
                writes += it.decodeToString()
                gate.await()
            }
        }
        outbound.reply("reply".encodeToByteArray())
        outbound.send("key".encodeToByteArray(), userInput = true)
        outbound.send("raw".encodeToByteArray())
        testScheduler.runCurrent()
        assertEquals(listOf("replykeyraw"), writes)
        assertEquals(1, callbacks)
        assertEquals(4, publication.intervalMillis(visible = true))
        assertEquals(16, publication.intervalMillis(visible = false))
        testScheduler.advanceTimeBy(80)
        assertEquals(16, publication.intervalMillis(visible = true))
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(2, callbacks)
        assertEquals(4, publication.intervalMillis(visible = true))
        testScheduler.advanceTimeBy(40)
        assertEquals(16, publication.intervalMillis(visible = true))
        writer.cancel()
    }

    @Test
    fun `cancelled write does not renew feedback and raw traffic never starts it`() = runTest {
        val outbound = TerminalOutbound()
        var callbacks = 0
        val gate = CompletableDeferred<Unit>()
        val writer = backgroundScope.launch {
            outbound.drainTo(onUserInputWrite = { callbacks++ }) { gate.await() }
        }
        outbound.send("raw".encodeToByteArray())
        outbound.reply("reply".encodeToByteArray())
        testScheduler.runCurrent()
        assertEquals(0, callbacks)
        outbound.send("key".encodeToByteArray(), userInput = true)
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(2, callbacks)
        writer.cancel()

        val blocked = TerminalOutbound()
        val blockedWriter = backgroundScope.launch {
            blocked.drainTo(onUserInputWrite = { callbacks++ }) { CompletableDeferred<Unit>().await() }
        }
        blocked.send("key".encodeToByteArray(), userInput = true)
        testScheduler.runCurrent()
        assertEquals(3, callbacks)
        blockedWriter.cancel()
        testScheduler.runCurrent()
        assertEquals(3, callbacks)
    }
}
