package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.ui.design.FolderItems
import app.skerry.ui.design.moveFolder
import app.skerry.ui.design.moveIntoFolder
import app.skerry.ui.design.renameFolder

/**
 * Manual sidebar sorting (drag-and-drop) for the flat profile list.
 *
 * Order's source of truth is the host list order itself (as in [app.skerry.shared.host.HostStore]):
 * there is no separate sort field on [Host]. Sidebar folders are derived from [Host.group] by first
 * appearance ([groupHostsByFolder]), and the moves preserve the invariant that hosts of one group
 * form a contiguous block. The moves themselves are [app.skerry.ui.design.moveIntoFolder] and its
 * two neighbours, shared with the snippet and runbook libraries; what is host-specific is
 * [HostFolderItems]; the filtered-view translation is
 * [app.skerry.ui.design.filteredIndexToFull], shared with the libraries.
 */

/**
 * How the sidebar's profiles answer a folder reordering ([FolderItems]).
 *
 * A blank/`null` group collapses to `null`, matching [groupHostsByFolder] (a single "Ungrouped"
 * folder); otherwise `null` and `""` would be two buckets and reordering "Ungrouped" would move only
 * part of its hosts. The bucket keeps its first-appearance place, because that is where the sidebar
 * draws it — unlike the snippet and runbook libraries, which pin it last.
 *
 * The sidebar's bucket is named, not synthetic: a host is free to carry the literal group
 * `Ungrouped` and then shares the bucket with the unfiled ones, so a drop into what looks unfiled
 * can file the host under that name. That is what the sidebar has always done
 * ([groupHostsByFolder]); the libraries avoid it with an unforgeable sentinel
 * ([app.skerry.ui.design.UNGROUPED_FOLDER]), which the host list cannot adopt without rewriting
 * every stored `Ungrouped` group.
 */
object HostFolderItems : FolderItems<Host> {
    override fun idOf(item: Host): String = item.id
    override fun folderOf(item: Host): String? = item.group
    override fun withFolder(item: Host, folder: String?): Host = item.copy(group = folder)
    override fun canonicalName(folder: String?): String? = folder?.takeIf { it.isNotBlank() }
    override val ungroupedLast: Boolean get() = false
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
): List<Host> = moveIntoFolder(hosts, HostFolderItems, setOf(hostId), targetGroup, targetIndexInGroup)

/**
 * Rename group [oldName] to [newName] across all profiles. Matching uses the canonical group key
 * (blank/`null` collapses to `null`); a blank/`null` [newName] ungroups the hosts (`Host.group` =
 * `null`) — the same path used to "delete" a group, moving its hosts to Ungrouped while keeping the
 * profiles. Unknown/blank [oldName] or old == new leaves the list unchanged.
 */
fun renameHostGroup(hosts: List<Host>, oldName: String?, newName: String?): List<Host> =
    renameFolder(hosts, HostFolderItems, oldName, newName)

/**
 * Move folder [group] as a whole to [targetGroupIndex] among folders (host order within it is
 * preserved). Index is clamped; unknown [group] leaves the list unchanged.
 */
fun moveGroup(hosts: List<Host>, group: String?, targetGroupIndex: Int): List<Host> =
    moveFolder(hosts, HostFolderItems, group, targetGroupIndex)
