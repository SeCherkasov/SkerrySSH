package app.skerry.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_ungrouped
import org.jetbrains.compose.resources.stringResource

/** Папка списка хостов: имя группы + хосты в ней (порядок исходного списка). */
@Immutable
data class HostFolder(val name: String, val hosts: List<Host>)

/**
 * ТЕХНИЧЕСКИЙ ключ синтетической корзины для профилей без группы (`Host.group` пуст/`null`).
 * Используется как ключ группировки в чистой логике ([groupHostsByFolder]) и в сравнениях
 * `folder.name != UNGROUPED_LABEL` — поэтому НЕ локализуется (иначе сломалась бы группировка при
 * смене языка). Для показа пользователю берите [ungroupedLabel].
 */
const val UNGROUPED_LABEL = "Ungrouped"

/** Локализованная подпись корзины «без группы» для отображения (не для группировки — см. [UNGROUPED_LABEL]). */
@Composable
fun ungroupedLabel(): String = stringResource(Res.string.shtail_ungrouped)

/**
 * Сгруппировать профили по [Host.group] для сайдбара. Папки идут в порядке первого появления
 * группы во входном списке, хосты внутри — в исходном порядке. Пустая/`null`-группа сводится в
 * корзину [ungroupedLabel]. Чистая функция (без Compose) — зафиксирована
 * [app.skerry.ui.host.HostGroupingTest], переиспользуется desktop/мобильным сайдбаром.
 */
fun groupHostsByFolder(hosts: List<Host>, ungroupedLabel: String = UNGROUPED_LABEL): List<HostFolder> {
    val buckets = LinkedHashMap<String, MutableList<Host>>()
    for (host in hosts) {
        val key = host.group?.takeIf { it.isNotBlank() } ?: ungroupedLabel
        buckets.getOrPut(key) { mutableListOf() }.add(host)
    }
    return buckets.map { (name, list) -> HostFolder(name, list) }
}
