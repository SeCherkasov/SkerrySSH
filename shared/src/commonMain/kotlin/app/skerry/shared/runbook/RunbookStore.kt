package app.skerry.shared.runbook

/**
 * Persistent store for saved runbooks. Same synchronous contract as
 * [app.skerry.shared.snippet.SnippetStore]: mutations are rare and UI-initiated; implementations
 * must be thread-safe.
 */
interface RunbookStore {
    /** All runbooks in insertion/update order. */
    fun all(): List<Runbook>

    /** Creates a new record or replaces the existing one with the same [Runbook.id] (upsert). */
    fun put(runbook: Runbook)

    /** Removes the record by id; missing id is a no-op. */
    fun remove(id: String)

    /**
     * Atomically computes and applies an order/content transform across all runbooks.
     * Implementations must ensure atomicity (e.g., via transaction) and preserve the id set.
     */
    fun reorder(transform: (List<Runbook>) -> List<Runbook>)
}
