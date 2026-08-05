package app.skerry.ui.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The debt file is what carries a reactivation rebuild across restarts, so what it can and cannot hold is
 * the whole point: a server URL is user-typed and may contain the separator, and an unreadable file must
 * not take the app down with it.
 */
class FileReconcileDebtStoreTest {

    private fun store() = FileReconcileDebtStore(
        Files.createTempDirectory("skerry-debts").resolve("sync-reconcile"),
    )

    @Test
    fun `debts survive a round trip through the file`() {
        val store = store()
        val debts = setOf(ServerLink("https://home.test", "maya"), ServerLink("https://work.test", "maya"))
        store.save(debts)
        assertEquals(debts, store.load(), "both links must come back")
    }

    @Test
    fun `a server URL carrying the separator or a newline stays one parseable link`() {
        val store = store()
        val awkward = ServerLink("https://host.test/path?a=b&c=d\nnot-a-line", "acc=ount")
        store.save(setOf(awkward))
        assertEquals(setOf(awkward), store.load(), "url-encoding must keep it on one line")
    }

    @Test
    fun `an empty set leaves no file behind`() {
        val path = Files.createTempDirectory("skerry-debts").resolve("sync-reconcile")
        val store = FileReconcileDebtStore(path)
        store.save(setOf(ServerLink("https://home.test", "maya")))
        assertTrue(Files.exists(path))

        store.save(emptySet())
        assertFalse(Files.exists(path), "a device that owes nothing carries no file")
        assertEquals(emptySet(), store.load(), "and reads as owing nothing")
    }

    @Test
    fun `a garbled file reads as no debts rather than throwing`() {
        val path = Files.createTempDirectory("skerry-debts").resolve("sync-reconcile")
        Files.writeString(path, "no-separator-here\n=leading\ntrailing=\n")
        assertEquals(emptySet(), FileReconcileDebtStore(path).load(), "unparseable lines are dropped, not thrown")
    }

    /**
     * One line truncated mid percent-escape (a kill during a write on a store that isn't atomic, or a
     * damaged filesystem) makes `URLDecoder` throw. That must cost that line only: taking the rest of the
     * file with it would read as "this device owes nothing" and push back every record the account purged.
     */
    @Test
    fun `a line with a broken escape costs its own line and not the file`() {
        val path = Files.createTempDirectory("skerry-debts").resolve("sync-reconcile")
        val intact = ServerLink("https://home.test", "maya")
        FileReconcileDebtStore(path).save(setOf(intact))
        Files.writeString(path, Files.readString(path) + "https%3A%2F%2Fwork.te%ZZ=maya\n")

        assertEquals(setOf(intact), FileReconcileDebtStore(path).load(), "the intact debt must survive")
    }
}
