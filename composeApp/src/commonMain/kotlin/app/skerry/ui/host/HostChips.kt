package app.skerry.ui.host

import androidx.compose.runtime.Composable
import app.skerry.shared.host.Host
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_chip_all
import org.jetbrains.compose.resources.stringResource

/**
 * Technical key of the "all hosts" chip at the start of the Hosts list filter row (desktop sidebar
 * and mobile). Used as the filter value in [filterHosts] and as the chip identity; not localized,
 * since that would break filtering on locale change. For display, use [allHostsChipLabel].
 */
const val ALL_HOSTS_CHIP = "All"

/** Localized "all hosts" chip label for display (not for filtering, see [ALL_HOSTS_CHIP]). */
@Composable
fun allHostsChipLabel(): String = stringResource(Res.string.shtail_chip_all)

/**
 * Filter chips: `All` plus unique host tags in order of first appearance (canonical form, no `#`).
 * List folders are built separately by [Host.group] ([app.skerry.ui.host.groupHostsByFolder]) —
 * group is the folder section, tag is the filter chip, two independent axes. Pure function, shared
 * by desktop and mobile lists.
 */
fun hostTagChips(hosts: List<Host>): List<String> = buildList {
    add(ALL_HOSTS_CHIP)
    val seen = LinkedHashSet<String>()
    for (host in hosts) for (tag in host.tags) if (seen.add(tag)) add(tag)
}

/** Chip label for display: localized for [ALL_HOSTS_CHIP], tags via [hostTagChipLabel]. */
@Composable
fun hostChipLabel(chip: String): String =
    if (chip == ALL_HOSTS_CHIP) allHostsChipLabel() else hostTagChipLabel(chip)

/** Tag chip label: `#` prefix (the model value has none). Pure, no localization involved. */
fun hostTagChipLabel(tag: String): String = "#$tag"

/**
 * Narrow [hosts] by the active chip ([activeChip] = tag, `All` = no filter) and [query] (AND).
 * Case-insensitive search across name/address/username/group/tags.
 */
fun filterHosts(hosts: List<Host>, activeChip: String = ALL_HOSTS_CHIP, query: String = ""): List<Host> {
    val needle = query.trim().lowercase()
    return hosts.filter { host ->
        val chipOk = activeChip == ALL_HOSTS_CHIP || activeChip in host.tags
        val queryOk = needle.isEmpty() || host.matchesQuery(needle)
        chipOk && queryOk
    }
}

/** [needle] is already lowercase; tags are stored lowercase (see normalizeTag). */
private fun Host.matchesQuery(needle: String): Boolean =
    label.lowercase().contains(needle) ||
        address.lowercase().contains(needle) ||
        username.lowercase().contains(needle) ||
        group?.lowercase()?.contains(needle) == true ||
        tags.any { it.contains(needle) }
