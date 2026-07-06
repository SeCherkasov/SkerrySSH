package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultSingletonStore
import kotlinx.serialization.Serializable

/**
 * What syncs between devices — an account-level setting (one for the whole account, not per
 * device): stored as an encrypted [RecordType.SETTINGS] record in the vault, so it travels over
 * the same E2E sync and applies uniformly everywhere.
 *
 * OFF semantics: "type doesn't participate in sync", without deletion. A disabled type is neither
 * pushed nor pulled, but local records on each device stay as-is, and blobs already uploaded to
 * the server just sit there (zero-knowledge, so the server can't read them anyway). No tombstones
 * are created on disable — that would erase data on other devices.
 *
 * Grouped as in the UI (WHAT SYNCS section): "Snippets" is its own toggle ([syncSnippets]);
 * everything forming a working connection (hosts/groups/credentials/keys/known-hosts/tunnels) is
 * under "Hosts & groups" ([syncHosts]). The settings record itself ([RecordType.SETTINGS]) always
 * syncs, otherwise a disable would never reach other devices.
 */
@Serializable
data class SyncSettings(
    val syncHosts: Boolean = true,
    val syncSnippets: Boolean = true,
) {
    /** Whether [type] syncs under the current flags. [RecordType.SETTINGS] always does. */
    fun shouldSync(type: RecordType): Boolean = when (type) {
        RecordType.SETTINGS -> true
        // Team keys and the identity pair carry access to Teams: always synced between a user's
        // devices, otherwise a team wouldn't open on a second device.
        RecordType.TEAM, RecordType.TEAM_IDENTITY -> true
        RecordType.SNIPPET -> syncSnippets
        RecordType.HOST,
        RecordType.GROUP,
        RecordType.IDENTITY,
        RecordType.CREDENTIAL,
        RecordType.KNOWN_HOST,
        RecordType.TUNNEL -> syncHosts
        // Terminal command history is local (per-host, encrypted in the vault) but never synced:
        // large, sensitive, and device-specific. Deliberately excluded from WHAT SYNCS.
        RecordType.TERMINAL_HISTORY -> false
    }
}

/**
 * Reads/writes [SyncSettings] as the single [RecordType.SETTINGS] record in [Vault] (a singleton
 * with fixed [SETTINGS_ID], following [app.skerry.shared.vault.WorkspaceLayoutStore]). On a locked
 * vault [load] returns the default (everything on), [save] requires an unlocked vault
 * ([Vault.put]). A corrupt/missing payload falls back to the default: a new or older vault without
 * a settings record syncs everything, matching pre-feature behavior.
 */
class SyncSettingsStore(private val vault: Vault) {

    // Corrupt payload / openPayload throw -> default to "sync everything" (see VaultSingletonStore):
    // the sync loop must not fail on an unreadable settings record (that would abort drainPull).
    private val store = VaultSingletonStore(vault, SETTINGS_ID, RecordType.SETTINGS, SyncSettings.serializer()) {
        SyncSettings()
    }

    fun load(): SyncSettings = store.load()

    fun save(settings: SyncSettings) {
        store.save(settings)
    }

    companion object {
        /** Stable id of the sync settings singleton record in the vault. */
        const val SETTINGS_ID = "sync.settings"
    }
}
