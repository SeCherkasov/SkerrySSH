package app.skerry.ui.host

import app.skerry.shared.host.Host

/**
 * Pure reorderings of the flat profile list for manual sidebar sorting (drag-and-drop).
 *
 * Order's source of truth is the host list order itself (as in [app.skerry.shared.host.HostStore]):
 * there is no separate sort field on [Host]. Sidebar folders are derived from [Host.group] by first
 * appearance ([groupHostsByFolder]); these functions preserve the invariant that hosts of one group
 * form a contiguous block, so the result is always flattened through group buckets.
 */

/**
 * Canonical group key: blank/`null` group collapses to `null`, matching [groupHostsByFolder]
 * (a single "Ungrouped" folder). Otherwise `null` and `""` would produce two buckets, and reordering
 * "Ungrouped" would move only part of its hosts.
 */
private fun String?.canonicalGroup(): String? = this?.takeIf { it.isNotBlank() }

/** "Group -> hosts" buckets in order of the group's first appearance (null-group is its own key). */
private fun bucketize(hosts: List<Host>): LinkedHashMap<String?, MutableList<Host>> {
    val buckets = LinkedHashMap<String?, MutableList<Host>>()
    for (host in hosts) buckets.getOrPut(host.group.canonicalGroup()) { mutableListOf() }.add(host)
    return buckets
}

/**
 * Move host [hostId] into group [targetGroup] at [targetIndexInGroup] among its hosts. Covers both
 * drag scenarios: reordering within a folder ([targetGroup] == current group) and moving to another
 * (rewriting [Host.group]). Index is clamped to a valid range; an emptied source group disappears.
 * Unknown [hostId] leaves the list unchanged.
 */
fun moveHostToGroup(
    hosts: List<Host>,
    hostId: String,
    targetGroup: String?,
    targetIndexInGroup: Int,
): List<Host> {
    val moving = hosts.firstOrNull { it.id == hostId } ?: return hosts
    val canonicalTarget = targetGroup.canonicalGroup()
    val buckets = LinkedHashMap<String?, MutableList<Host>>()
    for (host in hosts) {
        if (host.id == hostId) continue
        buckets.getOrPut(host.group.canonicalGroup()) { mutableListOf() }.add(host)
    }
    val target = buckets.getOrPut(canonicalTarget) { mutableListOf() }
    target.add(targetIndexInGroup.coerceIn(0, target.size), moving.copy(group = canonicalTarget))
    return buckets.values.flatten()
}

/**
 * Rename group [oldName] to [newName] across all profiles. Matching uses the canonical group key
 * (blank/`null` collapses to `null`); a blank/`null` [newName] ungroups the hosts (`Host.group` =
 * `null`) — the same path used to "delete" a group, moving its hosts to Ungrouped while keeping the
 * profiles. Result is flattened through group buckets, like [moveGroup]/[moveHostToGroup], to
 * preserve the contiguous-block invariant even when merging into an existing group. Unknown/blank
 * [oldName] or old==new leaves the list unchanged.
 */
fun renameHostGroup(hosts: List<Host>, oldName: String?, newName: String?): List<Host> {
    val from = oldName.canonicalGroup() ?: return hosts
    val to = newName.canonicalGroup()
    if (from == to) return hosts
    val renamed = hosts.map { if (it.group.canonicalGroup() == from) it.copy(group = to) else it }
    return bucketize(renamed).values.flatten()
}

/**
 * Move folder [group] as a whole to [targetGroupIndex] among folders (host order within it is
 * preserved). Index is clamped; unknown [group] leaves the list unchanged.
 */
fun moveGroup(hosts: List<Host>, group: String?, targetGroupIndex: Int): List<Host> {
    val canonical = group.canonicalGroup()
    val buckets = bucketize(hosts)
    val keys = buckets.keys.toMutableList()
    val from = keys.indexOf(canonical)
    if (from < 0) return hosts
    keys.removeAt(from)
    keys.add(targetGroupIndex.coerceIn(0, keys.size), canonical)
    return keys.flatMap { buckets.getValue(it) }
}
