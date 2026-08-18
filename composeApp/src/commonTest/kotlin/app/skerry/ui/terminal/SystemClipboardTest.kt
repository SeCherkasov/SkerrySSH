package app.skerry.ui.terminal

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import app.skerry.ui.design.FakeDirectClipboard
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which of the two clipboards a platform actually gets. Where a direct path exists (Wayland's
 * `wl-clipboard`) it owns CLIPBOARD outright: text written through Compose/AWT after it refused
 * would sit in the XWayland buffer while every read came from `wl-paste` — the split #282 is about,
 * reported to the user as a copy that worked.
 */
class SystemClipboardTest {

    @Test
    fun `a platform with no direct path writes through Compose`() = runTest {
        val compose = MemoryClipboard()
        val direct = FakeDirectClipboard(owns = false)
        systemClipboard(compose, direct).write("text")
        assertEquals("text", compose.text, "the write never reached the only clipboard this platform has")
        assertTrue(direct.writes.isEmpty(), "a platform without a direct path was asked to write through one")
    }

    @Test
    fun `the direct path takes the write and Compose is left alone`() = runTest {
        val compose = MemoryClipboard()
        val direct = FakeDirectClipboard(owns = true)
        systemClipboard(compose, direct).write("text")
        assertEquals(listOf("text"), direct.writes)
        assertNull(compose.text, "the text was also pushed into the Compose clipboard the direct path replaces")
    }

    @Test
    fun `a refused direct write fails rather than landing in the clipboard beside it`() = runTest {
        val compose = MemoryClipboard()
        val direct = FakeDirectClipboard(owns = true, accepts = false)
        assertFailsWith<IllegalStateException>("a refused write was reported as a copy that worked") {
            systemClipboard(compose, direct).write("text")
        }
        assertNull(compose.text, "a refused direct write fell back to the buffer no Wayland app pastes from")
    }

    @Test
    fun `the direct path answers reads and Compose is not consulted`() = runTest {
        val compose = MemoryClipboard().holding("stale")
        assertEquals("fresh", systemClipboard(compose, FakeDirectClipboard(owns = true, content = "fresh")).read())
    }

    /** An empty direct clipboard is an empty clipboard — not a reason to go read the other one. */
    @Test
    fun `a direct path with nothing on it does not fall back`() = runTest {
        val compose = MemoryClipboard().holding("stale")
        assertNull(systemClipboard(compose, FakeDirectClipboard(owns = true)).read())
    }

    /**
     * A `wl-paste` that could not answer is not an empty clipboard: reading it as one is how a paste
     * of nothing passed for a clipboard the user had just filled (#282), and where the direct path
     * owns CLIPBOARD there is no other buffer worth asking.
     */
    @Test
    fun `a direct read that could not answer fails rather than falling back to Compose`() = runTest {
        val compose = MemoryClipboard().holding("stale")
        val direct = FakeDirectClipboard(owns = true, refusesRead = true)
        assertFailsWith<IllegalStateException>("a clipboard that never answered read as empty") {
            systemClipboard(compose, direct).read()
        }
    }

    @Test
    fun `without a direct path reads come from Compose`() = runTest {
        val compose = MemoryClipboard().holding("text")
        assertEquals("text", systemClipboard(compose, FakeDirectClipboard(owns = false)).read())
    }
}

private suspend fun MemoryClipboard.holding(text: String): MemoryClipboard =
    apply { setClipEntry(plainTextClipEntry(text)) }

/**
 * The Compose clipboard, kept in memory instead of reaching for the developer's own. Text goes in
 * and out through the same platform wrappers the app uses, so this reads back on either target.
 */
private class MemoryClipboard : Clipboard {
    private var entry: ClipEntry? = null

    val text: String? get() = entry?.readPlainText()

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entry = clipEntry
    }

    // The code under test never asks for the platform handle; producing one would need a real
    // AWT/Android clipboard, which is what this fake exists to stay away from.
    override val nativeClipboard: NativeClipboard
        get() = error("the system clipboard itself is out of reach in a test")
}
