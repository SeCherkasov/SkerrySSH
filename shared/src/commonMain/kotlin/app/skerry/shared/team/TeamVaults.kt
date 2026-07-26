package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import okio.FileSystem
import okio.Path

/**
 * File-backed vaults of team share spaces: `<dir>/<teamId>.vault` for the team itself and
 * `<dir>/<teamId>__<scopeId>.vault` for each scope (see [TeamScopeRef]). The vault's dataKey is that
 * space's key, so record blobs stay wire-compatible between members: the server and every member
 * holding the key decrypt the same bytes. The keys themselves live in a TEAM record in the account
 * vault; this only opens/creates the files.
 * Instances are cached: one [Vault] per space per process (FileVault has its own internal lock).
 */
class TeamVaults(
    private val dir: Path,
    private val crypto: VaultCrypto,
    private val deviceId: String,
    private val fileSystem: FileSystem,
    private val harden: (Path) -> Unit = {},
    private val now: () -> String,
) {

    private val open = mutableMapOf<String, Vault>()

    /**
     * Outcome of [openOrClassify]. [StaleKey] and [Unreadable] both mean "no usable vault", but the
     * caller must treat them differently: a stale file (under a superseded key) is safe to drop and
     * rebuild from the server, whereas an unreadable (structurally corrupt) file must NOT be deleted
     * — that would silently destroy any local records that were never pushed.
     */
    sealed interface OpenResult {
        data class Opened(val vault: Vault) : OpenResult
        /** File structurally unlocks but its records don't decrypt under the given key (superseded key). */
        data object StaleKey : OpenResult
        /** File couldn't be read/parsed at all (corrupt meta) — preserve it, don't reset. */
        data object Unreadable : OpenResult
    }

    /**
     * Open (creating if needed) the space's vault. Returns null if the file can't be opened under
     * [key] — either a superseded key or a corrupt file. Callers that must distinguish the two
     * (to avoid deleting recoverable data) use [openOrClassify].
     */
    fun open(ref: TeamScopeRef, key: DataKey): Vault? =
        (openOrClassify(ref, key) as? OpenResult.Opened)?.vault

    /** Like [open] but classifies a failure as [OpenResult.StaleKey] vs [OpenResult.Unreadable]. */
    fun openOrClassify(ref: TeamScopeRef, key: DataKey): OpenResult {
        requireSafe(ref)
        open[ref.key]?.let { cached ->
            if (cached.isUnlocked) return OpenResult.Opened(cached)
        }
        // FileVault takes ownership of the passed key (and wipes it on lock), so hand it a copy
        // to keep the caller's instance valid across repeated open/lock cycles.
        val ownedKey = DataKey(key.bytes.copyOf())
        val vault = FileVault(
            path = dir / ref.fileName,
            crypto = crypto,
            deviceId = deviceId,
            fileSystem = fileSystem,
            harden = harden,
            now = now,
        )
        if (!vault.exists()) {
            fileSystem.createDirectories(dir)
            vault.createWithDataKey(ownedKey)
        } else {
            // Corrupt/unreadable file: unlockWithDataKey already wiped ownedKey. Don't reset — the
            // bytes may be a transient/partial write over records not yet pushed.
            if (vault.unlockWithDataKey(ownedKey) != UnlockResult.Success) return OpenResult.Unreadable
            // unlockWithDataKey doesn't validate the key (team-vault meta has no wrapping), so
            // validate by trial-decrypting the first live record. An empty vault accepts any key.
            // A decrypt failure here is a superseded key, not corruption — safe to reset.
            val probe = vault.records().firstOrNull { !it.deleted }
            if (probe != null && vault.openPayload(probe.id) == null) {
                vault.lock()
                return OpenResult.StaleKey
            }
        }
        open[ref.key] = vault
        return OpenResult.Opened(vault)
    }

    /** Lock and forget all open vaults (e.g. when the account vault locks). */
    fun lockAll() {
        open.values.forEach { it.lock() }
        open.clear()
    }

    /** Delete one space's file (left/deleted/access revoked): the local copy is no longer needed. */
    fun reset(ref: TeamScopeRef) {
        requireSafe(ref)
        open.remove(ref.key)?.lock()
        fileSystem.delete(dir / ref.fileName, mustExist = false)
    }

    /**
     * Delete the team's file **and every scope file under it**. Used when the team itself is gone
     * (leave/delete/removal): dropping only the team vault would leave a scope's records — which the
     * account no longer has any right to — sitting on disk.
     */
    fun resetTeam(teamId: String) {
        require(TeamScopeRef.isSafeId(teamId)) { "unsafe teamId" }
        reset(TeamScopeRef(teamId))
        val prefix = "${teamId}__"
        val files = runCatching { fileSystem.list(dir) }.getOrDefault(emptyList())
        files.map { it.name }
            .filter { it.startsWith(prefix) && it.endsWith(SUFFIX) }
            .forEach { reset(TeamScopeRef(teamId, it.removePrefix(prefix).removeSuffix(SUFFIX))) }
    }

    private fun requireSafe(ref: TeamScopeRef) {
        require(TeamScopeRef.isSafeId(ref.teamId)) { "unsafe teamId" }
        require(ref.isTeamWide || TeamScopeRef.isSafeId(ref.scopeId)) { "unsafe scopeId" }
    }

    private companion object {
        const val SUFFIX = ".vault"
    }
}
