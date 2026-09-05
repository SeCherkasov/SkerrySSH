package app.skerry.shared.snippet

import app.skerry.shared.vault.LibraryOrderStore
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.requireSameIds
import app.skerry.shared.vault.sortedByOrder

/**
 * [SnippetStore] over an encrypted [Vault]: each snippet is a [RecordType.SNIPPET] record whose
 * payload is the JSON serialization of [Snippet]. Commands may contain inline credentials, so they
 * get the same encryption and E2E sync as other secrets.
 *
 * Library order is stored apart, in [app.skerry.shared.vault.LibraryOrder] — [Vault.records] order
 * can't be relied on for it (putting an existing record doesn't move it, and a merge arrives in
 * server_seq order), which is the same reason the host tree has [app.skerry.shared.vault.WorkspaceLayout].
 * Reading a locked vault returns an empty list; a corrupt payload is silently skipped.
 */
class VaultSnippetStore(
    private val vault: Vault,
    /** Trash to snapshot deletions into; opt-in — see [app.skerry.shared.host.VaultHostStore]. */
    trash: TrashStore? = null,
    private val order: LibraryOrderStore = LibraryOrderStore(vault),
) : SnippetStore {

    private val codec = VaultRecordCodec(vault, RecordType.SNIPPET, Snippet.serializer(), trash) { it.label }

    override fun all(): List<Snippet> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list().sortedByOrder(order.read().snippets) { it.id }
    }

    /**
     * Saves the snippet and leaves the order record alone: an id the order doesn't mention sorts
     * to the end of the library ([sortedByOrder]), which is where a new snippet belongs. That is
     * also what carries a library written before the order record existed — it keeps the order it
     * has until the user drags something, instead of sinking below the first snippet added after
     * the upgrade.
     */
    override fun put(snippet: Snippet) {
        codec.put(snippet.id, snippet)
    }

    override fun remove(id: String) = vault.transaction {
        // Removal and the order update under one vault lock (like [reorder]): a mergeRemote from
        // background sync landing between the read and the write would otherwise be clobbered.
        codec.remove(id)
        // readOrNull, not read: an order record that exists but cannot be decrypted reads as an
        // empty order, and writing over it would drop the runbook order it also carries. The
        // snippet is removed either way; only the bookkeeping is left as it is.
        val current = order.readOrNull() ?: return@transaction
        if (id in current.snippets) order.write(current.copy(snippets = current.snippets - id))
    }

    override fun reorder(transform: (List<Snippet>) -> List<Snippet>) = vault.transaction {
        // Read-compute-write under one vault lock: a mergeRemote landing between the all() snapshot
        // and the write below would be clobbered with an order computed from a stale list.
        val current = all()
        val updated = transform(current)
        requireSameIds(current, updated) { it.id }
        // Only snippets whose content actually changed (a folder move rewrites Snippet.group) — a
        // pure reorder must not bump every record's version, that is sync traffic for nothing.
        // A snippet whose payload this build cannot decode is not in `current` at all (codec.list
        // skips it), so the order written below forgets where it sat — as the host tree does. It
        // reappears at the end of the library once the payload can be read again.
        val byId = current.associateBy { it.id }
        for (snippet in updated) {
            if (byId[snippet.id] != snippet) codec.put(snippet.id, snippet)
        }
        val existing = order.readOrNull() ?: return@transaction // see [remove]
        // Only when the order actually moved: a content-only transform (renaming a folder) would
        // otherwise bump the record on every call, and a device still holding an older order would
        // push it back over a reorder made elsewhere.
        val ids = updated.map { it.id }
        if (ids != existing.snippets) order.write(existing.copy(snippets = ids))
    }
}
