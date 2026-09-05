package app.skerry.shared.host

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.WorkspaceLayoutStore
import app.skerry.shared.vault.requireSameIds
import app.skerry.shared.vault.sortedByOrder

/**
 * [HostStore] over the encrypted [Vault]: each profile is a [RecordType.HOST] record whose payload
 * is the JSON serialization of [Host] (address/login/group/tags inside the encrypted blob). Tree
 * order is stored separately in [WorkspaceLayout] (a single record) to survive LWW sync
 * deterministically — [Vault.records] order can't be relied on (putting an existing record doesn't
 * move it, and merge arrives in server_seq order). Modeled on [app.skerry.shared.vault.CredentialStore].
 *
 * Requires an unlocked vault for mutations (like [CredentialStore]). Reading [all] on a locked vault
 * safely returns an empty list: the controller is built before the master password is entered and
 * reloads via `reload()` after unlock. A corrupt/unparseable profile is silently skipped — one bad
 * record doesn't break the whole list.
 */
class VaultHostStore(
    private val vault: Vault,
    private val layout: WorkspaceLayoutStore = WorkspaceLayoutStore(vault),
    /**
     * Trash to snapshot deletions into. Opt-in on purpose: the default deletes outright, so a store
     * built over a TEAM vault (which has no trash screen) can't silently keep a decrypted snapshot
     * of an un-shared secret readable by every member holding the teamKey. The platform entry points
     * pass a [TrashStore] for the personal vault.
     */
    trash: TrashStore? = null,
) : HostStore {

    private val codec = VaultRecordCodec(vault, RecordType.HOST, Host.serializer(), trash) { it.label }

    override fun all(): List<Host> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list().sortedByOrder(layout.read().hostOrder) { it.id }
    }

    override fun put(host: Host) = vault.transaction {
        // Profile write and layout read-modify-write under one vault lock (like [reorder]):
        // otherwise a concurrent mergeRemote from background sync landing between read() and write()
        // would be clobbered.
        codec.put(host.id, host)
        // readOrNull, not read: a layout record that exists but cannot be decrypted reads as an
        // empty layout, and writing over it would replace the whole account's host order with this
        // one host. The profile is saved either way; only the order is left as it is.
        val current = layout.readOrNull() ?: return@transaction
        if (host.id !in current.hostOrder) {
            layout.write(current.copy(hostOrder = current.hostOrder + host.id))
        }
    }

    override fun remove(id: String) = vault.transaction {
        // See [put]: layout update is atomic with the record removal.
        codec.remove(id)
        val current = layout.readOrNull() ?: return@transaction // see [put]
        if (id in current.hostOrder) {
            layout.write(current.copy(hostOrder = current.hostOrder - id))
        }
    }

    override fun reorder(transform: (List<Host>) -> List<Host>) = vault.transaction {
        // Read-compute-write under one vault lock: otherwise a concurrent mergeRemote from
        // background sync landing between the all() snapshot and the write below would be clobbered
        // with a stale order.
        val current = all()
        val updated = transform(current)
        requireSameIds(current, updated) { it.id }
        // Only rewrite profiles whose content actually changed (e.g. group via moveHostToGroup/
        // renameGroup) — a pure reorder shouldn't bump every record's version (extra sync traffic).
        val byId = current.associateBy { it.id }
        for (host in updated) {
            if (byId[host.id] != host) codec.put(host.id, host)
        }
        // Skipped like the writes above when the layout cannot be read: the record also holds the
        // group list, and writing the new host order over a record we could not read would replace
        // that list with an empty one. The profile changes above are already committed.
        val existing = layout.readOrNull() ?: return@transaction
        // Only when the order actually moved: a content-only transform (renameGroup, unbinding a
        // deleted secret) would otherwise bump the layout record on every call, and that record is
        // the whole account's host order under LWW — a device still holding an older order would
        // push it back over a reorder made elsewhere.
        val order = updated.map { it.id }
        if (order != existing.hostOrder) layout.write(existing.copy(hostOrder = order))
    }
}
