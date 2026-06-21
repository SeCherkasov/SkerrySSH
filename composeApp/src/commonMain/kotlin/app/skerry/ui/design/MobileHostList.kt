package app.skerry.ui.design

import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.groupHostsByFolder

/** Чип «все хосты» в начале фильтр-ленты списка Hosts мобильного макета `Skerry Mobile.html`. */
const val ALL_HOSTS_CHIP = "All"

/**
 * Готовый к рендеру список Hosts: лента фильтр-[chips] (All + группы) и отфильтрованные [sections]
 * (папки в порядке первого появления). Секции — те же [HostFolder], что у desktop-сайдбара.
 */
@Immutable
data class MobileHostList(
    val chips: List<String>,
    val sections: List<HostFolder>,
)

/**
 * Свести живой каталог [hosts] к виду списка макета. Чипы макета (#prod/#docker/…) проецируются на
 * реальную модель — у [Host] есть только [Host.group], тегов нет, поэтому чип = группа: `All` плюс
 * каждая непустая группа в порядке первого появления. [activeChip] (≠`All`) сужает до своей группы,
 * [query] дополнительно фильтрует по имени/адресу/пользователю/группе (без учёта регистра, AND с чипом).
 * Группировка отфильтрованного — общим [groupHostsByFolder] (безгруппные → секция «Ungrouped»).
 */
fun buildMobileHostList(
    hosts: List<Host>,
    query: String = "",
    activeChip: String = ALL_HOSTS_CHIP,
): MobileHostList {
    val chips = buildList {
        add(ALL_HOSTS_CHIP)
        val seen = LinkedHashSet<String>()
        for (host in hosts) {
            val group = host.group?.takeIf { it.isNotBlank() } ?: continue
            if (seen.add(group)) add(group)
        }
    }
    val needle = query.trim().lowercase()
    val filtered = hosts.filter { host ->
        val chipOk = activeChip == ALL_HOSTS_CHIP || host.group == activeChip
        val queryOk = needle.isEmpty() || host.matchesQuery(needle)
        chipOk && queryOk
    }
    return MobileHostList(chips = chips, sections = groupHostsByFolder(filtered))
}

/** [needle] уже в нижнем регистре. */
private fun Host.matchesQuery(needle: String): Boolean =
    label.lowercase().contains(needle) ||
        address.lowercase().contains(needle) ||
        username.lowercase().contains(needle) ||
        group?.lowercase()?.contains(needle) == true
