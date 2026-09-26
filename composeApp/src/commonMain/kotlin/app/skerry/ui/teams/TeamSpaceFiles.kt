package app.skerry.ui.teams

import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamVaults
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A share space's local state: its vault file and its delta cursors, which are only ever dropped
 * together. [SyncEngine][app.skerry.shared.sync.SyncEngine] moves a cursor past the records it
 * rejects, so a file rebuilt under a new key that resumed from the old tip would never receive what
 * the stale file already skipped.
 *
 * Every drop takes [syncLock], the lock a sync cycle holds: a cycle still running on the old file
 * files its tip when it finishes, and a reset that ran first would be undone by it.
 */
internal class TeamSpaceFiles(
    val vaults: TeamVaults,
    private val cursors: SyncStateStore,
    private val syncLock: Mutex,
) {

    /** Drops [ref]'s vault file and its cursors. */
    suspend fun reset(ref: TeamScopeRef) {
        syncLock.withLock { resetHeld(ref) }
    }

    /** [reset] for a caller that already holds the sync lock. */
    fun resetHeld(ref: TeamScopeRef) {
        vaults.reset(ref)
        forgetCursors(listOf(ref))
    }

    /**
     * Drops the vault files of [teamId] — the team's and every scope's — and the cursors of [spaces].
     * The caller reads [spaces] before it removes the team's key, which takes the scope keys with it.
     */
    suspend fun resetTeam(teamId: String, spaces: List<TeamScopeRef>) {
        syncLock.withLock {
            vaults.resetTeam(teamId)
            forgetCursors(spaces)
        }
    }

    /**
     * Clears the cursors of [refs] on every link they were ever synced on, not just the one live now:
     * a reset can follow a network round trip, so the session can be gone or on another server by the
     * time it runs — and a tip left standing is one a later sync resumes from, missing everything below it.
     */
    private fun forgetCursors(refs: List<TeamScopeRef>) {
        val suffixes = refs.map { it.key }
        cursors.keys().filter { key -> suffixes.any { key == it || key.endsWith("\u0000$it") } }
            .forEach { cursors.setCursor(it, 0) }
    }
}
