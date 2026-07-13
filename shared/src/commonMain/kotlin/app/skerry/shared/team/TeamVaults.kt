package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import okio.FileSystem
import okio.Path

/**
 * File-backed team vaults: `<dir>/<teamId>.vault`, dataKey = teamKey (record blobs are
 * wire-compatible between members: the server and other members decrypt with the same key).
 * The key lives in a TEAM record in the account vault; this only opens/creates the files.
 * Instances are cached: one [Vault] per team per process (FileVault has its own internal lock).
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
     * Open (creating if needed) the team's vault. Returns null if the file can't be opened under
     * [teamKey] — either a superseded key or a corrupt file. Callers that must distinguish the two
     * (to avoid deleting recoverable data) use [openOrClassify].
     */
    fun open(teamId: String, teamKey: DataKey): Vault? =
        (openOrClassify(teamId, teamKey) as? OpenResult.Opened)?.vault

    /** Like [open] but classifies a failure as [OpenResult.StaleKey] vs [OpenResult.Unreadable]. */
    fun openOrClassify(teamId: String, teamKey: DataKey): OpenResult {
        require(isSafeTeamId(teamId)) { "unsafe teamId" }
        open[teamId]?.let { cached ->
            if (cached.isUnlocked) return OpenResult.Opened(cached)
        }
        // FileVault takes ownership of the passed key (and wipes it on lock), so hand it a copy
        // to keep the caller's instance valid across repeated open/lock cycles.
        val ownedKey = DataKey(teamKey.bytes.copyOf())
        val vault = FileVault(
            path = dir / "$teamId.vault",
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
        open[teamId] = vault
        return OpenResult.Opened(vault)
    }

    /** Lock and forget all open vaults (e.g. when the account vault locks). */
    fun lockAll() {
        open.values.forEach { it.lock() }
        open.clear()
    }

    /** Delete the team's file (leave/delete/access revoked): the local copy is no longer needed. */
    fun reset(teamId: String) {
        require(isSafeTeamId(teamId)) { "unsafe teamId" }
        open.remove(teamId)?.lock()
        fileSystem.delete(dir / "$teamId.vault", mustExist = false)
    }

    private companion object {
        /** teamId is a client-generated UUID: [a-z0-9-] only, or the filename becomes a path injection. */
        fun isSafeTeamId(teamId: String): Boolean =
            teamId.isNotEmpty() && teamId.length <= 64 &&
                teamId.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}
