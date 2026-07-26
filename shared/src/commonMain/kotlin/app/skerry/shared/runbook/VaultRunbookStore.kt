package app.skerry.shared.runbook

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [RunbookStore] over an encrypted [Vault]: each runbook is a [RecordType.RUNBOOK] record whose
 * payload is the JSON serialization of [Runbook]. Like snippets, the commands can carry inline
 * credentials, so they get the same encryption and E2E sync.
 *
 * Runbooks have no defined order (set semantics), so no separate order record is needed; entries
 * come back in [Vault.records] order. Reading a locked vault returns an empty list; a corrupt
 * payload is silently skipped.
 */
class VaultRunbookStore(
    private val vault: Vault,
    /** Trash to snapshot deletions into; opt-in — see [app.skerry.shared.host.VaultHostStore]. */
    trash: TrashStore? = null,
) : RunbookStore {

    private val codec = VaultRecordCodec(vault, RecordType.RUNBOOK, Runbook.serializer(), trash) { it.label }

    override fun all(): List<Runbook> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    override fun put(runbook: Runbook) {
        codec.put(runbook.id, runbook)
    }

    override fun remove(id: String) {
        codec.remove(id)
    }
}
