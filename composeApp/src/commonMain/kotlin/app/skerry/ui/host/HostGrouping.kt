package app.skerry.ui.host

import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host

/** Папка списка хостов: имя группы + хосты в ней (порядок исходного списка). */
@Immutable
data class HostFolder(val name: String, val hosts: List<Host>)

/**
 * Сгруппировать профили по [Host.group] для сайдбара. Папки идут в порядке первого появления
 * группы во входном списке, хосты внутри — в исходном порядке. Пустая/`null`-группа сводится в
 * корзину [ungroupedLabel]. Чистая функция (без Compose) — зафиксирована
 * [app.skerry.ui.host.HostGroupingTest], переиспользуется desktop/мобильным сайдбаром.
 */
fun groupHostsByFolder(hosts: List<Host>, ungroupedLabel: String = "Ungrouped"): List<HostFolder> {
    val buckets = LinkedHashMap<String, MutableList<Host>>()
    for (host in hosts) {
        val key = host.group?.takeIf { it.isNotBlank() } ?: ungroupedLabel
        buckets.getOrPut(key) { mutableListOf() }.add(host)
    }
    return buckets.map { (name, list) -> HostFolder(name, list) }
}
