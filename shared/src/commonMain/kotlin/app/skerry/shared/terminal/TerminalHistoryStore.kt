package app.skerry.shared.terminal

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import kotlinx.serialization.Serializable

/**
 * Per-host persistence of terminal command history. [key] is a stable host identifier
 * (protocol+account+address+port); commands are stored newest-first. The vault-backed implementation
 * ([VaultTerminalHistoryStore]) gives encryption at rest; tests use in-memory.
 *
 * Security: only echoed input reaches here — password/passphrase input (no echo) is filtered out
 * upstream via [app.skerry.shared.ssh.ShellChannel.echoSuppressed] (see `TerminalScreenState.typeInput`).
 * Storing in the vault (not a plaintext file) keeps any secrets that do slip in under the same
 * encryption as hosts/keys.
 */
interface TerminalHistoryStore {
    /** History for [key], newest-first; empty if nothing saved or the vault is locked. */
    fun load(key: String): List<String>

    /**
     * Save [commands] (newest-first) for [key]; silent no-op on a locked vault. [label] is the
     * human-readable host name shown next to a command in the palette; `null` keeps whatever label
     * is already stored, so a caller that doesn't know it can't erase it.
     */
    fun save(key: String, commands: List<String>, label: String? = null)

    /** Every host's history, for cross-host search. Empty on a locked vault. */
    fun all(): List<TerminalHistoryRecord>
}

/**
 * [TerminalHistoryStore] over [Vault]: one [RecordType.TERMINAL_HISTORY] record per host key, payload
 * JSON [TerminalHistoryRecord]. The record id is deterministic from [key], so re-saving is an upsert.
 * [RecordType.TERMINAL_HISTORY] is excluded from sync
 * ([app.skerry.shared.sync.SyncSettings.shouldSync]) — history stays local to the device. The list is
 * capped at [cap] on save.
 */
class VaultTerminalHistoryStore(
    private val vault: Vault,
    private val cap: Int = 300,
) : TerminalHistoryStore {

    private val codec = VaultRecordCodec(vault, RecordType.TERMINAL_HISTORY, TerminalHistoryRecord.serializer())

    override fun load(key: String): List<String> {
        if (!vault.isUnlocked) return emptyList()
        return codec.get(idOf(key))?.commands ?: emptyList()
    }

    override fun save(key: String, commands: List<String>, label: String?) {
        if (!vault.isUnlocked) return
        val id = idOf(key)
        val keptLabel = label ?: codec.get(id)?.label
        codec.put(id, TerminalHistoryRecord(key, commands.take(cap), keptLabel))
    }

    override fun all(): List<TerminalHistoryRecord> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    private fun idOf(key: String): String = "$ID_PREFIX$key"

    private companion object {
        const val ID_PREFIX = "termhist:"
    }
}

/**
 * History record payload: host [key] + commands newest-first, plus the human-readable host [label]
 * shown in the command palette. [label] is nullable because records written before the palette
 * existed don't carry one.
 */
@Serializable
data class TerminalHistoryRecord(
    val key: String,
    val commands: List<String>,
    val label: String? = null,
)

/** Stable history key for a target host: protocol|account@address:port (see `SshTarget`). */
fun terminalHistoryKey(connectionType: String, username: String, host: String, port: Int): String =
    "$connectionType|$username@$host:$port"
