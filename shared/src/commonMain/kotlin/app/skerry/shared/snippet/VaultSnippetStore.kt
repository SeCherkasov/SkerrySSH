package app.skerry.shared.snippet

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.WorkspaceLayoutStore

/**
 * [SnippetStore] over an encrypted [Vault]: each snippet is a [RecordType.SNIPPET] record whose
 * payload is the JSON serialization of [Snippet]. Commands may contain inline credentials, so they
 * get the same encryption and E2E sync as other secrets.
 *
 * Order is stored in [WorkspaceLayout] to preserve manual drag-and-drop ordering across sync and
 * sessions. Reading a locked vault returns an empty list; a corrupt payload is silently skipped.
 */
class VaultSnippetStore(
    private val vault: Vault,
    /** Trash to snapshot deletions into; opt-in — see [app.skerry.shared.host.VaultHostStore]. */
    trash: TrashStore? = null,
    private val layout: WorkspaceLayoutStore = WorkspaceLayoutStore(vault),
) : SnippetStore {

    private val codec = VaultRecordCodec(vault, RecordType.SNIPPET, Snippet.serializer(), trash) { it.label }

    override fun all(): List<Snippet> {
        if (!vault.isUnlocked) return emptyList()
        val snippets = codec.list()
        val order = layout.read().snippetOrder
        if (order.isEmpty()) return snippets
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return snippets.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    override fun put(snippet: Snippet) = vault.transaction {
        codec.put(snippet.id, snippet)
        val current = layout.readOrNull() ?: return@transaction
        if (snippet.id !in current.snippetOrder) {
            layout.write(current.copy(snippetOrder = current.snippetOrder + snippet.id))
        }
    }

    override fun remove(id: String) = vault.transaction {
        codec.remove(id)
        val current = layout.readOrNull() ?: return@transaction
        if (id in current.snippetOrder) {
            layout.write(current.copy(snippetOrder = current.snippetOrder - id))
        }
    }

    override fun reorder(transform: (List<Snippet>) -> List<Snippet>) = vault.transaction {
        val current = all()
        val updated = transform(current)
        require(updated.size == current.size && updated.map { it.id }.toSet() == current.map { it.id }.toSet()) {
            "reorder must preserve the id set (had ${current.size}, got ${updated.size})"
        }
        val byId = current.associateBy { it.id }
        for (snippet in updated) {
            if (byId[snippet.id] != snippet) codec.put(snippet.id, snippet)
        }
        val existing = layout.readOrNull() ?: return@transaction
        val order = updated.map { it.id }
        if (order != existing.snippetOrder) layout.write(existing.copy(snippetOrder = order))
    }
}
