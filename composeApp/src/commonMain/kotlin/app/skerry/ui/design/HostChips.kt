package app.skerry.ui.design

import app.skerry.shared.host.Host

/** Чип «все хосты» в начале фильтр-ленты списка Hosts (desktop-сайдбар и `Skerry Mobile.html`). */
const val ALL_HOSTS_CHIP = "All"

/**
 * Фильтр-чипсы = `All` плюс уникальные теги хостов в порядке первого появления (каноническая форма
 * без `#`). Папки списка строятся отдельно по [Host.group] ([app.skerry.ui.host.groupHostsByFolder]):
 * группа = папка-секция, тег = чип-фильтр — две независимые оси. Чистая функция, зафиксирована
 * [HostChipsTest], переиспользуется desktop/мобильным списком.
 */
fun hostTagChips(hosts: List<Host>): List<String> = buildList {
    add(ALL_HOSTS_CHIP)
    val seen = LinkedHashSet<String>()
    for (host in hosts) for (tag in host.tags) if (seen.add(tag)) add(tag)
}

/** Подпись чипа на экране: `All` как есть, тег — с префиксом `#` (значение в модели — без `#`). */
fun hostChipLabel(chip: String): String = if (chip == ALL_HOSTS_CHIP) chip else "#$chip"

/**
 * Сузить [hosts] активным чипом ([activeChip] = тег, `All` — без фильтра) и строкой [query] (AND).
 * Поиск без учёта регистра по имени/адресу/пользователю/группе/тегам («Search hosts, tags…»).
 */
fun filterHosts(hosts: List<Host>, activeChip: String = ALL_HOSTS_CHIP, query: String = ""): List<Host> {
    val needle = query.trim().lowercase()
    return hosts.filter { host ->
        val chipOk = activeChip == ALL_HOSTS_CHIP || activeChip in host.tags
        val queryOk = needle.isEmpty() || host.matchesQuery(needle)
        chipOk && queryOk
    }
}

/** [needle] уже в нижнем регистре; теги хранятся в нижнем регистре (см. normalizeTag). */
private fun Host.matchesQuery(needle: String): Boolean =
    label.lowercase().contains(needle) ||
        address.lowercase().contains(needle) ||
        username.lowercase().contains(needle) ||
        group?.lowercase()?.contains(needle) == true ||
        tags.any { it.contains(needle) }
