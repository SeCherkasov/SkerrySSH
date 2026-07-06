package app.skerry.ui.snippet

/**
 * Parses a snippet tag string into a list: separators are comma/space/newline/tab, a leading `#`
 * is stripped, empties and duplicates are dropped, order is preserved. Shared by the desktop
 * ([SnippetsView]) and mobile (`MobileSnippetsView`) editors; tag casing isn't canonicalized,
 * unlike host tags — see [snippetTagSuggestions].
 */
fun parseSnippetTags(text: String): List<String> =
    text.split(',', ' ', '\n', '\t')
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .distinct()
