package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.IonspinVaultCrypto
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A space's reset waits for the sync cycle holding the lock: a cycle still running on the old file
 * files its tip when it finishes, and a reset that slipped in before that would leave the rebuilt,
 * empty file resuming from that tip — never receiving the records below it.
 */
class TeamSpaceFilesTest {

    @Test
    fun `a reset waits for the running sync cycle before clearing the cursor`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("team-space-files").toString().toPath()
        val vaults = TeamVaults(dir, IonspinVaultCrypto(), "device", FileSystem.SYSTEM, now = { "2026-09-26T00:00:00Z" })
        val cursors = InMemorySyncStateStore()
        val ref = TeamScopeRef("team")
        val cursorKey = "server\u0000${ref.key}"
        val syncLock = Mutex()
        val files = TeamSpaceFiles(vaults, cursors, syncLock)

        syncLock.lock()
        val reset = launch { files.reset(ref) }
        yield()
        // The cycle holding the lock files its tip; the reset must come after it, not before.
        cursors.setCursor(cursorKey, 42)
        syncLock.unlock()
        reset.join()

        assertEquals(0, cursors.cursor(cursorKey))
    }
}
