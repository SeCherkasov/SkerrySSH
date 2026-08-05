package app.skerry.android

import app.skerry.ui.sync.ServerLink
import app.skerry.ui.sync.SyncConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android halves of the two sync stores. Both are plain JVM file code (no Android framework), and both
 * carry state that decides whether this device rebuilds its vault before it pushes — a divergence from the
 * desktop twins ([app.skerry.ui.sync.FileReconcileDebtStore], `FileSyncConfigStore`) is silent and costs
 * records the account purged, so the same behaviours are pinned here.
 */
class AndroidSyncStoresTest {

    private fun tempFile(name: String): File =
        Files.createTempDirectory("skerry-android-stores").resolve(name).toFile()

    @Test
    fun `debts survive a round trip, separators and all`() {
        val file = tempFile("sync-reconcile")
        val store = AndroidReconcileDebtStore(file)
        val debts = setOf(
            ServerLink("https://home.test", "maya"),
            ServerLink("https://host.test/path?a=b\nnot-a-line", "acc=ount"),
        )
        store.save(debts)
        assertEquals(debts, store.load(), "url-encoding must keep every link on one parseable line")
    }

    @Test
    fun `an empty set leaves no file behind`() {
        val file = tempFile("sync-reconcile")
        val store = AndroidReconcileDebtStore(file)
        store.save(setOf(ServerLink("https://home.test", "maya")))
        assertTrue(file.exists())

        store.save(emptySet())
        assertFalse(file.exists(), "a device that owes nothing carries no file")
        assertEquals(emptySet(), store.load(), "and reads as owing nothing")
    }

    @Test
    fun `a line with a broken escape costs its own line and not the file`() {
        val file = tempFile("sync-reconcile")
        val intact = ServerLink("https://home.test", "maya")
        AndroidReconcileDebtStore(file).save(setOf(intact))
        file.appendText("https%3A%2F%2Fwork.te%ZZ=maya\n")

        assertEquals(setOf(intact), AndroidReconcileDebtStore(file).load(), "the intact debt must survive")
    }

    /**
     * The 0.2.1 config file carried the reconcile intent as `pendingReconcile=true`. It has to be read
     * back as [SyncConfig.legacyPendingReconcile] — that flag is the only thing the migration in
     * `SyncCoordinator`'s init has to go on — and it must not be written again, or the debt would be
     * re-imported after the reconcile that discharged it.
     */
    @Test
    fun `the legacy reconcile marker is read once and never written back`() {
        val file = tempFile("sync.json")
        file.writeText("serverUrl=https%3A%2F%2Fsync.test\naccountId=maya\ndeviceId=devA\npendingReconcile=true\n")
        val store = AndroidSyncConfigStore(file)

        val loaded = store.load()!!
        assertTrue(loaded.legacyPendingReconcile, "the old marker must reach the migration")

        store.save(loaded.copy(legacyPendingReconcile = false))
        assertFalse(file.readText().contains("pendingReconcile"), "and must not be written again")
        assertFalse(store.load()!!.legacyPendingReconcile)
    }
}
