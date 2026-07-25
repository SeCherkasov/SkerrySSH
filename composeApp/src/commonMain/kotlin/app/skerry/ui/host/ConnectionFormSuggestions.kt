package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.tag.PROD_TAG
import app.skerry.shared.tag.normalizeTag

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
 * Tag suggestions for the Tags inline input: [PROD_TAG] first, then unique tags from all [hosts]
 * (canonical form, see [normalizeTag]), excluding already-[selected] tags, narrowed by [query]
 * (also canonicalized, substring match). First-seen order for the rest. Pure function.
 *
 * `prod` is offered even when no host carries it yet — it is the tag that arms the production guard
 * ([app.skerry.shared.guard.ProductionGuard]), so it must be one tap away on the very first host,
 * not something to be typed from memory. It is filtered by [query] like any other tag.
 */
fun tagSuggestions(hosts: List<Host>, selected: List<String>, query: String = ""): List<String> {
    val taken = selected.toHashSet()
    val needle = normalizeTag(query)
    val seen = LinkedHashSet<String>()
    return buildList {
        if (PROD_TAG !in taken && (needle == null || PROD_TAG.contains(needle))) {
            seen.add(PROD_TAG)
            add(PROD_TAG)
        }
        for (host in hosts) for (tag in host.tags) {
            if (tag in taken || tag in seen) continue
            if (needle != null && !tag.contains(needle)) continue
            seen.add(tag)
            add(tag)
        }
    }
}
