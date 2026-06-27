package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet

/**
 * Type-ahead подсказки тегов для редактора сниппета: уникальные теги всех [snippets] в порядке первого
 * появления, исключая уже добавленные [selected] (сравнение без учёта регистра), сужённые набранным
 * [query] (подстрока без учёта регистра; пустой/пробельный — все). Регистр самих тегов сохраняется
 * «как сохранён» (теги сниппета не канонизируются, в отличие от тегов хоста). Чистая функция,
 * зафиксирована [app.skerry.ui.snippet.SnippetSuggestionsTest].
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
