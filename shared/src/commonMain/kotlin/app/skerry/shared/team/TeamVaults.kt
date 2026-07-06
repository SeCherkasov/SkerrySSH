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
     * Open (creating if needed) the team's vault. Returns null if the key doesn't match the
     * existing file (e.g. the team was recreated with a new key); reset the file via [reset].
     */
    fun open(teamId: String, teamKey: DataKey): Vault? {
        require(isSafeTeamId(teamId)) { "unsafe teamId" }
        open[teamId]?.let { cached ->
            if (cached.isUnlocked) return cached
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
            if (vault.unlockWithDataKey(ownedKey) != UnlockResult.Success) return null
            // unlockWithDataKey doesn't validate the key (team-vault meta has no wrapping), so
            // validate by trial-decrypting the first live record. An empty vault accepts any key.
            val probe = vault.records().firstOrNull { !it.deleted }
            if (probe != null && vault.openPayload(probe.id) == null) {
                vault.lock()
                return null
            }
        }
        open[teamId] = vault
        return vault
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
