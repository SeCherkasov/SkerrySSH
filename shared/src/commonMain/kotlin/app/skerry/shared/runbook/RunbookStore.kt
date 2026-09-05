package app.skerry.shared.runbook

/**
 * Persistent store for saved runbooks. Same synchronous contract as
 * [app.skerry.shared.snippet.SnippetStore]: mutations are rare and UI-initiated; implementations
 * must be thread-safe.
 */
interface RunbookStore {
    /** All runbooks in library order: what [reorder] last set, then anything it doesn't cover. */
    fun all(): List<Runbook>

    /** Creates a new record or replaces the existing one with the same [Runbook.id] (upsert). */
    fun put(runbook: Runbook)

    /** Removes the record by id; missing id is a no-op. */
    fun remove(id: String)

    /**
     * Atomic reorder: [transform] receives the library in its current order and returns the new one
     * (with updated [Runbook.group] when moving between folders). Read, compute and write happen
     * under one lock, so an order computed from a stale snapshot can't clobber a concurrent write
     * (a merge from background sync). The id set must not change: implementations reject a result
     * that lost, gained or duplicated an entry, and the error must not carry the [Runbook] values
     * themselves — they hold commands.
     *
     * Content changes in the result are saved either way, but the new order itself is dropped when
     * the store cannot read where it keeps it — a caller that shows the order back to the user has
     * to expect it to come back as it was on the next read.
     */
    fun reorder(transform: (List<Runbook>) -> List<Runbook>)
}
