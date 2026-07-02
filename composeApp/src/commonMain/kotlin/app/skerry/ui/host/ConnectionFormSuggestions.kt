package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.host.normalizeTag

/**
 * Уникальные непустые группы существующих [hosts] в порядке первого появления — выпадающие подсказки
 * для поля «Group» формы коннекта (рядом со свободным вводом своей группы). Значения отдаются как
 * хранятся в [Host.group] (trim, регистр сохраняется — группа отображается «как введена»). [query]
 * сужает список подстрокой без учёта регистра под уже набранный текст; пустой/пробельный — все.
 * Чистая функция, зафиксирована [ConnectionFormSuggestionsTest].
 */
fun groupSuggestions(hosts: List<Host>, query: String = ""): List<String> {
    val needle = query.trim().lowercase()
    val seen = LinkedHashSet<String>()
    return buildList {
        for (host in hosts) {
            val group = host.group?.trim()
            if (group.isNullOrEmpty()) continue
            if (needle.isNotEmpty() && !group.lowercase().contains(needle)) continue
            if (seen.add(group)) add(group)
        }
    }
}

/**
 * Подсказки тегов для инлайн-ввода блока «Tags»: уникальные теги всех [hosts] (каноническая форма,
 * см. [normalizeTag]), исключая уже добавленные [selected], сужённые набранным [query] (тоже
 * канонизируется и матчится подстрокой). Порядок первого появления. Чистая функция.
 */
fun tagSuggestions(hosts: List<Host>, selected: List<String>, query: String = ""): List<String> {
    val taken = selected.toHashSet()
    val needle = normalizeTag(query)
    val seen = LinkedHashSet<String>()
    return buildList {
        for (host in hosts) for (tag in host.tags) {
            if (tag in taken || tag in seen) continue
            if (needle != null && !tag.contains(needle)) continue
            seen.add(tag)
            add(tag)
        }
    }
}
