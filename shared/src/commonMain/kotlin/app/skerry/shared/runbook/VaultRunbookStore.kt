package app.skerry.shared.runbook

import app.skerry.shared.vault.LibraryOrderStore
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.requireSameIds
import app.skerry.shared.vault.sortedByOrder

/**
 * [RunbookStore] over an encrypted [Vault]: each runbook is a [RecordType.RUNBOOK] record whose
 * payload is the JSON serialization of [Runbook]. Like snippets, the commands can carry inline
 * credentials, so they get the same encryption and E2E sync.
 *
 * Library order is stored apart, in [app.skerry.shared.vault.LibraryOrder], for the reasons spelled
 * out in [app.skerry.shared.snippet.VaultSnippetStore]. Reading a locked vault returns an empty
 * list; a corrupt payload is silently skipped.
 */
class VaultRunbookStore(
    private val vault: Vault,
    /** Trash to snapshot deletions into; opt-in — see [app.skerry.shared.host.VaultHostStore]. */
    trash: TrashStore? = null,
    private val order: LibraryOrderStore = LibraryOrderStore(vault),
) : RunbookStore {

    private val codec = VaultRecordCodec(vault, RecordType.RUNBOOK, Runbook.serializer(), trash) { it.label }

    override fun all(): List<Runbook> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list().sortedByOrder(order.read().runbooks) { it.id }
    }

    /** Leaves the order record alone — see [app.skerry.shared.snippet.VaultSnippetStore.put]. */
    override fun put(runbook: Runbook) {
        codec.put(runbook.id, runbook)
    }

    override fun remove(id: String) = vault.transaction {
        // Removal and the order update under one vault lock; readOrNull so an unreadable record
        // isn't replaced (it also carries the snippet order). See [VaultSnippetStore.remove].
        codec.remove(id)
        val current = order.readOrNull() ?: return@transaction
        if (id in current.runbooks) order.write(current.copy(runbooks = current.runbooks - id))
    }

    override fun reorder(transform: (List<Runbook>) -> List<Runbook>) = vault.transaction {
        // Read-compute-write under one vault lock — see [VaultSnippetStore.reorder] for each step.
        val current = all()
        val updated = transform(current)
        requireSameIds(current, updated) { it.id }
        val byId = current.associateBy { it.id }
        for (runbook in updated) {
            if (byId[runbook.id] != runbook) codec.put(runbook.id, runbook)
        }
        val existing = order.readOrNull() ?: return@transaction
        val ids = updated.map { it.id }
        if (ids != existing.runbooks) order.write(existing.copy(runbooks = ids))
    }
}
