package app.skerry.shared.runbook

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * How many runs are kept per runbook. A procedure run daily would otherwise grow the vault — and
 * with it every device's sync — without end, and nobody reads the fortieth-last deploy.
 */
private const val DEFAULT_KEEP_PER_RUNBOOK = 20

/**
 * History of past runs over an encrypted [Vault]: each run is a [RecordType.RUNBOOK_RUN] record
 * holding a [RunbookRunRecord]. Encrypted and synced like the runbook it belongs to — the record
 * carries host labels, which name real machines.
 *
 * No trash: a run is an event, not a thing to restore, and the cap deletes old ones by design.
 * Reading a locked vault returns an empty list and writing to one does nothing, as everywhere else.
 */
class VaultRunbookRunStore(
    private val vault: Vault,
    private val keepPerRunbook: Int = DEFAULT_KEEP_PER_RUNBOOK,
) {

    private val codec = VaultRecordCodec(vault, RecordType.RUNBOOK_RUN, RunbookRunRecord.serializer())

    /** Runs of [runbookId], newest first. */
    fun forRunbook(runbookId: String): List<RunbookRunRecord> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list().filter { it.runbookId == runbookId }.sortedByDescending { it.startedAt }
    }

    /**
     * Writes [record] and drops whatever falls outside the cap for its runbook. Both happen in one
     * transaction: a merge landing between them would otherwise see a history one entry over the
     * cap and could resurrect the run this call has just dropped.
     */
    fun record(record: RunbookRunRecord) {
        if (!vault.isUnlocked) return
        vault.transaction {
            codec.put(record.id, record)
            forRunbook(record.runbookId).drop(keepPerRunbook).forEach { codec.remove(it.id) }
        }
    }

    /** Drops the whole history of [runbookId] — what deleting the runbook itself leaves behind. */
    fun forget(runbookId: String) {
        if (!vault.isUnlocked) return
        vault.transaction { forRunbook(runbookId).forEach { codec.remove(it.id) } }
    }
}
