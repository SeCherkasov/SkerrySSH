package app.skerry.ui.design

import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.groupHostsByFolder

/**
 * Готовый к рендеру список Hosts: лента фильтр-[chips] (`All` + теги) и отфильтрованные [sections]
 * (папки по группам в порядке первого появления). Секции — те же [HostFolder], что у desktop-сайдбара.
 */
@Immutable
data class MobileHostList(
    val chips: List<String>,
    val sections: List<HostFolder>,
)

/**
 * Свести живой каталог [hosts] к виду списка макета. Чипсы — теги хостов ([hostTagChips]),
 * [activeChip] (≠`All`) сужает до хостов с этим тегом, [query] дополнительно фильтрует (AND).
 * Папки отфильтрованного — общим [groupHostsByFolder] по [Host.group] (безгруппные → «Ungrouped»).
 * Логика чипсов/фильтра — общая с desktop-сайдбаром ([filterHosts]).
 */
fun buildMobileHostList(
    hosts: List<Host>,
    query: String = "",
    activeChip: String = ALL_HOSTS_CHIP,
): MobileHostList = MobileHostList(
    chips = hostTagChips(hosts),
    sections = groupHostsByFolder(filterHosts(hosts, activeChip, query)),
)
