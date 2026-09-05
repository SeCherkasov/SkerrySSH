package app.skerry.shared.snippet

/**
 * Persistent store for saved snippets. The synchronous contract assumes rare, UI-initiated
 * mutations. Implementations must be thread-safe.
 */
interface SnippetStore {
    /** All snippets in library order: what [reorder] last set, then anything it doesn't cover. */
    fun all(): List<Snippet>

    /** Creates a new record or replaces the existing one with the same [Snippet.id] (upsert). */
    fun put(snippet: Snippet)

    /** Removes the record by id; missing id is a no-op. */
    fun remove(id: String)

    /**
     * Atomic reorder: [transform] receives the library in its current order and returns the new one
     * (with updated [Snippet.group] when moving between folders). Read, compute and write happen
     * under one lock, so an order computed from a stale snapshot can't clobber a concurrent write
     * (a merge from background sync). The id set must not change: implementations reject a result
     * that lost, gained or duplicated an entry, and the error must not carry the [Snippet] values
     * themselves — they hold commands.
     *
     * Content changes in the result are saved either way, but the new order itself is dropped when
     * the store cannot read where it keeps it — a caller that shows the order back to the user has
     * to expect it to come back as it was on the next read.
     */
    fun reorder(transform: (List<Snippet>) -> List<Snippet>)
}
