package app.skerry.ui.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The on-disk half of the #170 migration. Up to 0.2.1 the reconcile intent was the key
 * `pendingReconcile=true` inside `sync.json`; the flag it now loads as
 * ([SyncConfig.legacyPendingReconcile]) is the only thing [SyncCoordinator]'s startup migration has to go
 * on, and a device that upgrades mid-rebuild would otherwise lose it silently.
 */
class FileSyncConfigStoreLegacyTest {

    @Test
    fun `the legacy reconcile marker is read once and never written back`() {
        val path = Files.createTempDirectory("skerry-config").resolve("sync.json")
        Files.writeString(path, "serverUrl=https%3A%2F%2Fsync.test\naccountId=maya\ndeviceId=devA\npendingReconcile=true\n")
        val store = FileSyncConfigStore(path)

        val loaded = store.load()!!
        assertTrue(loaded.legacyPendingReconcile, "the old marker must reach the migration")

        store.save(loaded.copy(legacyPendingReconcile = false))
        assertFalse(Files.readString(path).contains("pendingReconcile"), "and must not be written again")
        assertFalse(store.load()!!.legacyPendingReconcile)
    }
}
