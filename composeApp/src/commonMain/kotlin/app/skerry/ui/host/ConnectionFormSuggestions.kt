package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.host.normalizeTag

/**
 * Unique non-empty groups from [hosts] in first-seen order, for the connection form's Group field
 * suggestions. Values are as stored in [Host.group] (trimmed, case preserved). [query] narrows the
 * list by case-insensitive substring; empty/blank returns all. Pure function.
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
 * Tag suggestions for the Tags inline input: unique tags from all [hosts] (canonical form, see
 * [normalizeTag]), excluding already-[selected] tags, narrowed by [query] (also canonicalized,
 * substring match). First-seen order. Pure function.
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
