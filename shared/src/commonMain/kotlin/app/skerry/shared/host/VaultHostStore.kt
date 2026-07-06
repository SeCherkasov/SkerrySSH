package app.skerry.shared.host

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.WorkspaceLayoutStore

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
) : HostStore {

    private val codec = VaultRecordCodec(vault, RecordType.HOST, Host.serializer())

    override fun all(): List<Host> {
        if (!vault.isUnlocked) return emptyList()
        val hosts = codec.list()
        val order = layout.read().hostOrder
        val rank = order.withIndex().associate { (i, id) -> id to i }
        // Stable sort: hosts outside the order (new/synced) keep records()' relative order and are
        // appended at the end.
        return hosts.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    override fun put(host: Host) = vault.transaction {
        // Profile write and layout read-modify-write under one vault lock (like [reorder]):
        // otherwise a concurrent mergeRemote from background sync landing between read() and write()
        // would be clobbered.
        codec.put(host.id, host)
        val current = layout.read()
        if (host.id !in current.hostOrder) {
            layout.write(current.copy(hostOrder = current.hostOrder + host.id))
        }
    }

    override fun remove(id: String) = vault.transaction {
        // See [put]: layout update is atomic with the record removal.
        codec.remove(id)
        val current = layout.read()
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
        // Size + id set: a set-equality check alone would miss a duplicate (e.g. [A,B,C,A]), which
        // would corrupt hostOrder and tree order (associate keeps the last index for a duplicate id).
        require(updated.size == current.size && updated.map { it.id }.toSet() == current.map { it.id }.toSet()) {
            "reorder must preserve the id set (had ${current.size}, got ${updated.size})"
        }
        // Only rewrite profiles whose content actually changed (e.g. group via moveHostToGroup/
        // renameGroup) — a pure reorder shouldn't bump every record's version (extra sync traffic).
        val byId = current.associateBy { it.id }
        for (host in updated) {
            if (byId[host.id] != host) codec.put(host.id, host)
        }
        layout.write(layout.read().copy(hostOrder = updated.map { it.id }))
    }
}
