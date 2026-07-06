package app.skerry.shared.snippet

/**
 * Persistent store for saved snippets. The synchronous contract assumes rare, UI-initiated
 * mutations. Implementations must be thread-safe.
 */
interface SnippetStore {
    /** All snippets in insertion/update order. */
    fun all(): List<Snippet>

    /** Creates a new record or replaces the existing one with the same [Snippet.id] (upsert). */
    fun put(snippet: Snippet)

    /** Removes the record by id; missing id is a no-op. */
    fun remove(id: String)
}
