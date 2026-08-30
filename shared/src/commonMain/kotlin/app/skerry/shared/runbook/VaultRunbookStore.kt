package app.skerry.shared.runbook

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.WorkspaceLayoutStore

/**
 * [RunbookStore] over an encrypted [Vault]: each runbook is a [RecordType.RUNBOOK] record whose
 * payload is the JSON serialization of [Runbook]. Like snippets, the commands can carry inline
 * credentials, so they get the same encryption and E2E sync.
 *
 * Order is stored in [WorkspaceLayout] to preserve manual drag-and-drop ordering across sync and
 * sessions. Reading a locked vault returns an empty list; a corrupt payload is silently skipped.
 */
class VaultRunbookStore(
    private val vault: Vault,
    /** Trash to snapshot deletions into; opt-in — see [app.skerry.shared.host.VaultHostStore]. */
    trash: TrashStore? = null,
    private val layout: WorkspaceLayoutStore = WorkspaceLayoutStore(vault),
) : RunbookStore {

    private val codec = VaultRecordCodec(vault, RecordType.RUNBOOK, Runbook.serializer(), trash) { it.label }

    override fun all(): List<Runbook> {
        if (!vault.isUnlocked) return emptyList()
        val runbooks = codec.list()
        val order = layout.read().runbookOrder
        if (order.isEmpty()) return runbooks
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return runbooks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    override fun put(runbook: Runbook) = vault.transaction {
        codec.put(runbook.id, runbook)
        val current = layout.readOrNull() ?: return@transaction
        if (runbook.id !in current.runbookOrder) {
            layout.write(current.copy(runbookOrder = current.runbookOrder + runbook.id))
        }
    }

    override fun remove(id: String) = vault.transaction {
        codec.remove(id)
        val current = layout.readOrNull() ?: return@transaction
        if (id in current.runbookOrder) {
            layout.write(current.copy(runbookOrder = current.runbookOrder - id))
        }
    }

    override fun reorder(transform: (List<Runbook>) -> List<Runbook>) = vault.transaction {
        val current = all()
        val updated = transform(current)
        require(updated.size == current.size && updated.map { it.id }.toSet() == current.map { it.id }.toSet()) {
            "reorder must preserve the id set (had ${current.size}, got ${updated.size})"
        }
        val byId = current.associateBy { it.id }
        for (runbook in updated) {
            if (byId[runbook.id] != runbook) codec.put(runbook.id, runbook)
        }
        val existing = layout.readOrNull() ?: return@transaction
        val order = updated.map { it.id }
        if (order != existing.runbookOrder) layout.write(existing.copy(runbookOrder = order))
    }
}
