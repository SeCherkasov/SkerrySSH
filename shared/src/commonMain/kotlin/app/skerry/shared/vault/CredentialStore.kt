package app.skerry.shared.vault

import app.skerry.shared.text.normalizeGroup

/**
 * Store for [Credential] keychain secrets over a [Vault]: each secret is a [RecordType.CREDENTIAL]
 * record whose payload is a JSON serialization of [Credential] (label and secret inside the
 * encrypted blob). Pure common logic over the [Vault] contract — no platform part.
 *
 * Requires an unlocked vault for mutations: CRUD on a locked one throws from [Vault] itself.
 * Reading [all] on a locked vault safely returns an empty list (like [app.skerry.shared.host.VaultHostStore]):
 * sync-driven reloads may race a lock and must degrade, not crash. Records whose payload fails to
 * decrypt or parse (corruption/incompatible migration) are silently skipped — one broken record
 * must not break the whole list.
 */
class CredentialStore(
    private val vault: Vault,
    /** Trash to snapshot deletions into; opt-in — see [app.skerry.shared.host.VaultHostStore]. */
    trash: TrashStore? = null,
) {

    private val codec = VaultRecordCodec(vault, RecordType.CREDENTIAL, Credential.serializer(), trash) { it.label }

    /** All live secrets (tombstones and other record types excluded); empty on a locked vault. */
    fun all(): List<Credential> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    /** Secret by [id], or `null` if missing, deleted, or unreadable. */
    fun get(id: String): Credential? = codec.get(id)

    /** Create/update a secret (upsert by [Credential.id]). */
    fun put(credential: Credential) {
        codec.put(credential.id, credential)
    }

    /**
     * [put] for the forms, which build a whole [Credential] from their fields and carry neither the
     * note nor the folder: the stored [Credential.note] and [Credential.group] are kept rather than
     * blanked. Read and write are one [Vault.transaction] for the same reason [edit] is — a merge
     * landing between them would let the write carry a note the record no longer has, or drop one it
     * just gained.
     *
     * A record that does not exist yet keeps whatever the caller passed, which is how a freshly
     * created secret can still be born with a note. A **deleted** one is left alone, as in [edit]:
     * [get] answers `null` for a tombstone as well as for an absent id, and writing through that
     * would raise the deleted secret from the dead on every synced device.
     */
    fun putKeepingMeta(credential: Credential) = vault.transaction {
        if (isTombstoned(credential.id)) return@transaction
        val stored = get(credential.id)
        put(if (stored == null) credential else credential.copy(note = stored.note, group = stored.group))
    }

    /** Whether [id] is a credential this vault has already deleted (as opposed to never seen). */
    private fun isTombstoned(id: String): Boolean =
        vault.records().any { it.id == id && it.type == RecordType.CREDENTIAL && it.deleted }

    /**
     * Edits what a secret is called in place: keeps its [Credential.id] (hosts reference secrets by id, not
     * label) and its secret material, replacing only the [Credential.label], [Credential.note] and
     * [Credential.group].
     * The re-[put] bumps the record version, so the change propagates to other devices via sync like
     * any other edit. No-op if [id] is missing or deleted — a tombstone must not be resurrected under
     * a new name.
     *
     * The read-check-write runs in one [Vault.transaction] (like [app.skerry.shared.host.VaultHostStore]):
     * otherwise a concurrent [Vault.mergeRemote] from background sync could land a tombstone between the
     * [get] and the [put], and the [put] would resurrect the deleted record under the new label and push
     * that un-delete to every device.
     */
    fun edit(id: String, label: String, note: String?, group: String?) = vault.transaction {
        val existing = get(id) ?: return@transaction
        // The store is where a folder name is made canonical, as it is for snippets and runbooks:
        // the grouping key is the name itself, so two names that draw alike have to be one name, no
        // matter which form wrote them.
        put(existing.copy(label = label, note = note, group = normalizeGroup(group)))
    }

    /** Soft-delete a secret (tombstone). Hosts referencing it are reconciled in the UI layer. */
    fun remove(id: String) {
        codec.remove(id)
    }
}
