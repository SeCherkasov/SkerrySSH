package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet

/**
 * Type-ahead tag suggestions for the snippet editor: unique tags across all [snippets] in
 * first-appearance order, excluding [selected] (case-insensitive), filtered by [query]
 * (case-insensitive substring; blank matches all). Tag casing is preserved as stored, unlike
 * host tags. See [app.skerry.ui.snippet.SnippetSuggestionsTest].
 */
fun snippetTagSuggestions(snippets: List<Snippet>, selected: List<String>, query: String = ""): List<String> {
    val taken = selected.mapTo(HashSet()) { it.lowercase() }
    val needle = query.trim().lowercase()
    val seen = LinkedHashSet<String>()
    return buildList {
        for (snippet in snippets) for (tag in snippet.tags) {
            val key = tag.lowercase()
            if (key in taken || key in seen) continue
            if (needle.isNotEmpty() && !key.contains(needle)) continue
            seen.add(key)
            add(tag)
        }
    }
}
