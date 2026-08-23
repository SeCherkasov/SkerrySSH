package app.skerry.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_type_container
import app.skerry.ui.generated.resources.conn_type_local
import app.skerry.ui.generated.resources.conn_type_mosh
import app.skerry.ui.generated.resources.conn_type_serial
import app.skerry.ui.design.UNGROUPED_FOLDER
import app.skerry.ui.design.ungroupedFolderLabel
import app.skerry.ui.generated.resources.conn_type_ssh
import app.skerry.ui.generated.resources.conn_type_telnet
import app.skerry.ui.generated.resources.conn_type_vnc
import app.skerry.ui.generated.resources.conn_type_rdp
import org.jetbrains.compose.resources.stringResource

/** A host list folder: group name plus its hosts (in source list order). */
@Immutable
data class HostFolder(val name: String, val hosts: List<Host>)

/**
 * Technical key for the synthetic bucket holding hosts without a group (`Host.group` blank/`null`).
 * Used as the grouping key in [groupHostsByFolder] and in `folder.name != UNGROUPED_LABEL`
 * comparisons; not localized, since that would break grouping on locale change. For display, use
 * [ungroupedLabel].
 *
 * A name a host *can* carry, unlike the bucket the other lists use ([UNGROUPED_FOLDER]), and
 * deliberately so: here a group called `Ungrouped` falls into the bucket instead of standing beside
 * it, which is the sidebar's own long-standing rule and the one its empty groups are checked
 * against. The newer lists key their bucket by something no record can hold, so a folder somebody
 * names `Ungrouped` stays a folder of its own there.
 */
const val UNGROUPED_LABEL = "Ungrouped"

/** Localized "ungrouped" bucket label for display (not for grouping, see [UNGROUPED_LABEL]). */
@Composable
fun ungroupedLabel(): String = ungroupedFolderLabel()

/**
 * Group hosts by [Host.group] for the sidebar. Folders appear in order of the group's first
 * appearance in the input list, hosts within a folder keep source order. Blank/`null` group falls
 * into the [ungroupedLabel] bucket. Pure function (no Compose), shared by desktop and mobile sidebars.
 */
fun groupHostsByFolder(hosts: List<Host>, ungroupedLabel: String = UNGROUPED_LABEL): List<HostFolder> {
    val buckets = LinkedHashMap<String, MutableList<Host>>()
    for (host in hosts) {
        val key = host.group?.takeIf { it.isNotBlank() } ?: ungroupedLabel
        buckets.getOrPut(key) { mutableListOf() }.add(host)
    }
    return buckets.map { (name, list) -> HostFolder(name, list) }
}

/**
 * Folders for one section's sidebar: the host-derived folders of [sectionHosts], then the user's
 * [emptyGroups] that have no folder yet.
 *
 * A group name is global (it lives in [Host.group]), so an empty group is only a placeholder for a
 * folder that has no host to carry it. It is dropped here as soon as any profile in [allHosts] uses
 * the name: the folder then belongs to whichever section those hosts are in, and showing it as an
 * empty folder in the other one is the bug this guards against.
 */
fun sidebarFolders(
    sectionHosts: List<Host>,
    allHosts: List<Host>,
    emptyGroups: List<String>,
): List<HostFolder> {
    val folders = groupHostsByFolder(sectionHosts)
    val taken = folders.mapTo(mutableSetOf()) { it.name }
    allHosts.mapNotNullTo(taken) { it.group?.takeIf(String::isNotBlank) }
    return folders + emptyGroups.distinct().filterNot { it in taken }.map { HostFolder(it, emptyList()) }
}

/**
 * Split hosts into connection-type sub-groups in [ConnectionType] order, keeping only types that are
 * present and each type's hosts in source order. Used to sub-group the no-group bucket by transport.
 */
fun groupHostsByConnectionType(hosts: List<Host>): List<Pair<ConnectionType, List<Host>>> =
    ConnectionType.entries.mapNotNull { type ->
        hosts.filter { it.connectionType == type }.takeIf { it.isNotEmpty() }?.let { type to it }
    }

/** Localized display name of a connection transport (protocol names stay as-is across locales). */
@Composable
fun connectionTypeLabel(type: ConnectionType): String = stringResource(
    when (type) {
        ConnectionType.SSH -> Res.string.conn_type_ssh
        ConnectionType.MOSH -> Res.string.conn_type_mosh
        ConnectionType.TELNET -> Res.string.conn_type_telnet
        ConnectionType.SERIAL -> Res.string.conn_type_serial
        ConnectionType.VNC -> Res.string.conn_type_vnc
        ConnectionType.RDP -> Res.string.conn_type_rdp
        ConnectionType.LOCAL -> Res.string.conn_type_local
        ConnectionType.CONTAINER -> Res.string.conn_type_container
    },
)
