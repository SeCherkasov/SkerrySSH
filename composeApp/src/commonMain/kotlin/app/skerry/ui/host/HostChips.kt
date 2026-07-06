package app.skerry.ui.host

import app.skerry.shared.host.Host

/** "All hosts" chip at the start of the Hosts list filter row (desktop sidebar and mobile). */
const val ALL_HOSTS_CHIP = "All"

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

/** Chip label for display: `All` as-is, tags prefixed with `#` (model value has no `#`). */
fun hostChipLabel(chip: String): String = if (chip == ALL_HOSTS_CHIP) chip else "#$chip"

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
