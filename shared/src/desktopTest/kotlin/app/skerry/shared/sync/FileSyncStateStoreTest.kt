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

    @Test
    fun `setCursor is read back in the same instance`() {
        val store = FileSyncStateStore(file())
        store.setCursor("acc", 42L)
        assertEquals(42L, store.cursor("acc"))
    }

    @Test
    fun `cursor survives reload from disk`() {
        FileSyncStateStore(file()).setCursor("acc", 99L)
        // Свежий экземпляр на том же пути читает сохранённое — это и есть смысл персистентности
        // (иначе каждый перезапуск процесса делал бы полный re-pull since 0).
        assertEquals(99L, FileSyncStateStore(file()).cursor("acc"))
    }

    @Test
    fun `accounts are independent`() {
        val store = FileSyncStateStore(file())
        store.setCursor("a", 1L)
        store.setCursor("b", 2L)
        assertEquals(1L, store.cursor("a"))
        assertEquals(2L, store.cursor("b"))
        // И после reload оба сохраняются.
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
