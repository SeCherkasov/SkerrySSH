package app.skerry.ui.mobile

import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.filterHosts
import app.skerry.ui.host.hostTagChips

/**
 * Render-ready Hosts list: filter [chips] row (`All` + tags) and filtered [sections] (folders by
 * group, in first-seen order). Sections are the same [HostFolder] as the desktop sidebar.
 */
@Immutable
data class MobileHostList(
    val chips: List<String>,
    val sections: List<HostFolder>,
)

/**
 * Reduces the live [hosts] catalog to the mobile list shape. Chips are host tags ([hostTagChips]);
 * [activeChip] (≠ `All`) narrows to hosts with that tag, [query] filters further (AND). Folders
 * over the filtered result via [groupHostsByFolder] on [Host.group] (ungrouped → "Ungrouped").
 * Chip/filter logic is shared with the desktop sidebar ([filterHosts]).
 */
fun buildMobileHostList(
    hosts: List<Host>,
    query: String = "",
    activeChip: String = ALL_HOSTS_CHIP,
): MobileHostList = MobileHostList(
    chips = hostTagChips(hosts),
    sections = groupHostsByFolder(filterHosts(hosts, activeChip, query)),
)
