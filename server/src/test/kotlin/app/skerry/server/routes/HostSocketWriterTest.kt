package app.skerry.server.routes

import app.skerry.server.share.GuestFrame
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two coroutines write to the host's socket, and a `from:` line names only the frame that comes
 * immediately after it (#312). Nothing else on that socket may land between the two — a viewer list
 * arriving in the gap leaves the host holding keystrokes it cannot attribute, which is a control
 * prompt that never appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSocketWriterTest {

    /** What reached the socket, as one readable line per frame. */
    private fun Frame.describe(): String = when (this) {
        is Frame.Text -> readText()
        is Frame.Binary -> "binary:${data.decodeToString()}"
        else -> "other"
    }

    @Test
    fun `a viewer list cannot land between a from line and the frame it names`() = runTest {
        val written = mutableListOf<String>()
        // A real socket suspends between two sends; without that the pair is atomic by accident and
        // the test would pass with the lock removed.
        val writer = HostSocketWriter { frame ->
            written += frame.describe()
            yield()
        }

        launch { writer.input(GuestFrame("mate@x.io", "ls\n".encodeToByteArray())) }
        launch { writer.viewers(listOf("mate@x.io", "other@x.io")) }
        advanceUntilIdle()

        val from = "from:" + Base64.getEncoder().encodeToString("mate@x.io".toByteArray())
        assertEquals(from, written[0])
        assertEquals("binary:ls\n", written[1], "the viewer list was written inside the pair")
        assertEquals(3, written.size)
        assertEquals("viewers:2:", written[2].substringBefore(Base64.getEncoder().encodeToString("mate@x.io".toByteArray())))
    }

    /** Base64 for the same reason the viewer list uses it: an account id must not carry a separator. */
    @Test
    fun `an account id cannot introduce a separator of its own`() = runTest {
        val written = mutableListOf<String>()
        val writer = HostSocketWriter { written += it.describe() }

        writer.viewers(listOf("a:b,c@x.io", "plain@x.io"))

        val encoded = written.single().removePrefix("viewers:2:")
        assertEquals(2, encoded.split(",").size)
        assertEquals("a:b,c@x.io", Base64.getDecoder().decode(encoded.split(",").first()).decodeToString())
    }
}
