package app.skerry.shared.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSyncStateStoreTest {

    private val dir: Path = Files.createTempDirectory("skerry-cursor-test")
    private fun file(): Path = dir.resolve("sync-cursor.json")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `unknown account starts at zero`() {
        val store = FileSyncStateStore(file())
        assertEquals(0L, store.cursor("acc"))
    }

    /**
     * The account cursor is filed under a whole link — url, NUL, account id (`ServerLink.cursorKey`) — and
     * the separator is only safe because this store encodes what it writes. A key that came back cut at
     * the NUL would file every server's cursor under its own url.
     */
    @Test
    fun `a link key survives the file with its separator intact`() {
        val key = "https://work.test\u0000maya"
        FileSyncStateStore(file()).setCursor(key, 7L)
        val reopened = FileSyncStateStore(file())
        assertEquals(7L, reopened.cursor(key))
        assertEquals(0L, reopened.cursor("https://work.test"), "the key must not read as its own prefix")
        assertEquals(0L, reopened.cursor("maya"), "nor as the account id it ends with")
    }

    @Test
    fun `setCursor is read back in the same instance`() {
        val store = FileSyncStateStore(file())
        store.setCursor("acc", 42L)
        assertEquals(42L, store.cursor("acc"))
    }

    @Test
    fun `cursor survives reload from disk`() {
        FileSyncStateStore(file()).setCursor("acc", 99L)
        // A fresh instance at the same path reads back the saved value — that's the point of
        // persistence (otherwise every process restart would do a full re-pull since 0).
        assertEquals(99L, FileSyncStateStore(file()).cursor("acc"))
    }

    @Test
    fun `accounts are independent`() {
        val store = FileSyncStateStore(file())
        store.setCursor("a", 1L)
        store.setCursor("b", 2L)
        assertEquals(1L, store.cursor("a"))
        assertEquals(2L, store.cursor("b"))
        // Both persist across reload too.
        val reloaded = FileSyncStateStore(file())
        assertEquals(1L, reloaded.cursor("a"))
        assertEquals(2L, reloaded.cursor("b"))
    }

    @Test
    fun `corrupt file is treated as empty`() {
        Files.write(file(), "this is not a cursor file\n###".toByteArray())
        val store = FileSyncStateStore(file())
        assertEquals(0L, store.cursor("acc"))
    }

    @Test
    fun `account id with separators and newlines round-trips`() {
        val tricky = "acc=with\nnewline&and=equals"
        val store = FileSyncStateStore(file())
        store.setCursor(tricky, 7L)
        assertEquals(7L, FileSyncStateStore(file()).cursor(tricky))
    }
}
