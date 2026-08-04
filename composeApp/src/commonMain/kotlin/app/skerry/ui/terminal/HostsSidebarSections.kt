package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.shared.ssh.isRemoteDesktop
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalTeams
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamScopeRef
import androidx.compose.runtime.collectAsState
import app.skerry.ui.teams.AutoPullTeamsOnOnline
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_add_first
import app.skerry.ui.generated.resources.rd_no_desktops
import app.skerry.ui.generated.resources.term_recent_section
import app.skerry.ui.host.HostDragState
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostGroup
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection
import app.skerry.ui.host.MockHost
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.color
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.connectionTypeLabel
import app.skerry.ui.host.groupHostsByConnectionType
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.host.icon
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Host folder header: collapse chevron + icon + name + count. The chevron ([collapsed] ->
 * `chevron_right`, else `expand_more`) toggles the folder ([onToggle]); the click target is the icon
 * only, so it doesn't interfere with dragging the header (folder reorder).
 */
@Composable
private fun FolderHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit, onEdit: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        }
        Sym("folder_open", size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(name, color = Skerry.colors.dim, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // Rename/delete the group (live catalog only, not for the synthetic "Ungrouped").
        if (onEdit != null) IconBtn("edit", onClick = onEdit, box = 20, icon = 13.sp, tint = Skerry.colors.faint)
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.card).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
        }
    }
}

/** RECENT section header in the sidebar (shared by the live and mock paths). */
@Composable
internal fun RecentSectionHeader() {
    SidebarSectionTitle(
        stringResource(Res.string.term_recent_section),
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun TeamHostsSectionHeader() {
    SidebarSectionTitle(
        stringResource(Res.string.lib_teams_sidebar),
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * Shared team-hosts sections below the personal catalog: one section per active team with a
 * received key that has hosts in its team vault. Hosts are read from the per-team vault; the reread
 * is keyed on ([hostsSnapshot], team list) so it refreshes after reloadManagers updates the catalog
 * post-team-sync. Click uses the same [LocalConnectHost] path (secret is prompted since credential
 * links are stripped on share).
 */
@Composable
internal fun TeamHostsSection(hostsSnapshot: List<Host>, state: DesktopDesignState, section: HostSection, mono: FontFamily) {
    val teams = LocalTeams.current ?: return
    // Pulls shared team hosts when sync transitions to Online, see AutoPullTeamsOnOnline.
    AutoPullTeamsOnOnline()
    val teamList by teams.teams.collectAsState()
    // Changes on every team sync, so hosts freshly pulled into the team vault appear without a
    // manual sync (the personal catalog doesn't change, and these sections read the vault directly).
    val revision by teams.revision.collectAsState()
    val sections = remember(teamList, hostsSnapshot, revision, section) {
        teamList.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.flatMap { team ->
            // One group per share space: the team itself, plus every scope whose key we hold. A scope
            // we're merely told about (manager without a grant) has no readable records, so no group.
            val spaces = listOf(TeamScopeRef(team.id) to team.name) +
                team.scopes.filter { it.hasKey }.map { TeamScopeRef(team.id, it.id) to "${team.name} · ${it.name}" }
            spaces.mapNotNull { (ref, label) ->
                val vault = teams.spaceVault(ref) ?: return@mapNotNull null
                // Shared hosts are split by section like the personal catalog: a team's VNC box belongs
                // to the desktops list, its servers to the terminal one.
                val shared = VaultHostStore(vault).all().inSection(section)
                if (shared.isEmpty()) null else label to shared
            }
        }
    }
    if (sections.isEmpty()) return
    TeamHostsSectionHeader()
    sections.forEach { (name, shared) ->
        // Prefixed collapse key: otherwise a team and a host group with the same name would share
        // one entry in the common collapsedGroups.
        val collapseKey = "$TEAM_COLLAPSE_PREFIX$name"
        val collapsed = state.isGroupCollapsed(collapseKey)
        val onToggle = remember(state, collapseKey) { { state.toggleGroupCollapsed(collapseKey) } }
        TeamFolderHeader(name, shared.size, collapsed, onToggle)
        if (!collapsed) {
            shared.forEach { host -> key("team-${host.id}") { TeamHostRow(host, mono) } }
        }
    }
}

/** Collapse-key prefix for teams in the shared [DesktopDesignState.collapsedGroups], see [TeamHostsSection]. */
private const val TEAM_COLLAPSE_PREFIX = "\u0000team\u0000"

/**
 * Team header in the sidebar, modeled on [FolderHeader] (collapse chevron + icon + name + count) to
 * visually match host folders; differs only in the `group` icon marking its team-vault origin.
 */
@Composable
private fun TeamFolderHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        }
        Sym("group", size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(name, color = Skerry.colors.dim, size = 12.5.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.card).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
        }
    }
}

@Composable
internal fun HostGroupBlock(group: HostGroup, state: DesktopDesignState, section: HostSection, mono: FontFamily) {
    // The mock catalog is split by section too, so the preview of each sidebar shows its own kind of
    // host; a folder left with nothing to show is skipped entirely.
    val hosts = group.hosts.filter { mockHostSection(it) == section }
    if (hosts.isEmpty()) return
    val collapsed = state.isGroupCollapsed(group.name)
    val onToggleCollapsed = remember(state, group.name) { { state.toggleGroupCollapsed(group.name) } }
    Column(Modifier.padding(bottom = 2.dp)) {
        FolderHeader(group.name, hosts.size, collapsed, onToggleCollapsed)
        if (!collapsed) {
            Column(Modifier.padding(start = 22.dp)) {
                hosts.forEach { host -> HostRow(host, state, mono) }
            }
        }
    }
}

/** Section of a preview-catalog row ([MockHost] carries a transport but is not a saved profile). */
private fun mockHostSection(host: MockHost): HostSection =
    if (host.connectionType.isRemoteDesktop) HostSection.RemoteDesktops else HostSection.Terminal

/** Note shown instead of an empty column when a section's catalog holds nothing yet. */
@Composable
internal fun EmptyCatalogNote() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)) {
        Txt(stringResource(Res.string.rd_no_desktops), color = Skerry.colors.dim, size = 12.sp)
        Txt(
            stringResource(Res.string.rd_add_first),
            color = Skerry.colors.faint, size = 11.5.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Live catalog folder: same visuals, sourced from [HostFolder] over [HostManagerController]. Clicking
 * a host connects via [LocalConnectHost]; the status dot and highlight come from live sessions
 * ([LocalSessions]) — status-dot color reflects the most recent session's connection state.
 *
 * Manual reorder ([dragState]): dragging the folder header reorders folders; dragging a host row
 * reorders within a folder or moves it to another (see [HostSidebarDnd]). Drops commit through
 * [controller]; [foldersProvider] supplies the current folder list at gesture time.
 */
@Composable
internal fun LiveHostFolder(
    folder: HostFolder,
    state: DesktopDesignState,
    section: HostSection,
    mono: FontFamily,
    dragState: HostDragState,
    controller: HostManagerController,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    foldersProvider: () -> List<HostFolder>,
) {
    val sessions = LocalSessions.current
    val connect = LocalConnectHost.current
    // Folder's group key: an empty folder uses its own name (like FolderBounds), otherwise the first
    // host's group. The synthetic "Ungrouped" folder is the null group.
    val group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL }
    val collapsed = state.isGroupCollapsed(folder.name)
    // Stabilizes the collapse lambda on (state, folder name), like the row lambdas below: otherwise
    // every folder recomposition (every frame during a drag) would redraw the header.
    val onToggleCollapsed = remember(state, folder.name) { { state.toggleGroupCollapsed(folder.name) } }
    // Edit pencil in the header, except for the synthetic "Ungrouped" bucket (not renameable).
    val onEditGroup = if (folder.name == UNGROUPED_LABEL) null
        else remember(state, folder.name) { { state.openRenameGroup(folder.name) } }
    // Highlights the target folder while a host is dragged over it.
    val isDropTarget = dragState.draggingHostId != null && dragState.activeHostDrop?.group == group
    val folderAlpha = if (dragState.draggingFolderName == folder.name) 0.4f else 1f
    // Insertion line within the folder: the index excludes the dragged host (like moveHostToGroup),
    // so it's anchored to visible rows via neighbors from the same filtered list.
    val others = folder.hosts.filter { it.id != dragState.draggingHostId }
    val dropIndex = if (isDropTarget) dragState.activeHostDrop?.index?.coerceIn(0, others.size) else null
    val lineBeforeId = dropIndex?.takeIf { it < others.size }?.let { others[it].id }
    Column(
        Modifier
            .padding(bottom = 2.dp)
            .alpha(folderAlpha)
            .clip(RoundedCornerShape(6.dp))
            // After clip, so bounds match the folder's visible (rounded) area, not its corners.
            .folderRangeAnchor(dragState, folder.name)
            .border(1.dp, if (isDropTarget) Skerry.colors.cyan else Color.Transparent, RoundedCornerShape(6.dp)),
    ) {
        Box(
            Modifier.folderHeaderAnchor(dragState, folder.name)
                // Section-aware: the drop index counts the folders this sidebar shows, not the
                // catalog's (a folder of the other section is invisible here and keeps its place).
                .draggableFolderHeader(dragState, folder.name, foldersProvider) { index ->
                    controller.moveFolderInSection(group, index, section)
                },
        ) {
            // The synthetic bucket shows the localized "no group" label; real folders show their name.
            val headerName = if (folder.name == UNGROUPED_LABEL) ungroupedLabel() else folder.name
            FolderHeader(headerName, folder.hosts.size, collapsed, onToggleCollapsed, onEditGroup)
        }
        // A collapsed folder shows only the header; the host list (and its drag targets) is hidden.
        if (!collapsed) Column(Modifier.padding(start = 22.dp)) {
            if (folder.name == UNGROUPED_LABEL) {
                // No-group bucket: sub-group by connection type with a small header per transport.
                // Reorder insertion lines are dropped here (ordering a typeless bucket is moot); a
                // host can still be dragged out to a real folder, which owns its own drop target.
                groupHostsByConnectionType(folder.hosts).forEach { (type, typeHosts) ->
                    HostTypeSubheader(connectionTypeLabel(type))
                    typeHosts.forEach { host ->
                        key(host.id) {
                            HostRow(host, state, section, controller, sessions, connect, mono, selectedHostId, onSelectHost, dragState, foldersProvider)
                        }
                    }
                }
            } else {
                // key(host.id): row positional identity is pinned to the host, so an open menu/row
                // state doesn't jump to a neighbor when the catalog reorders after an edit.
                folder.hosts.forEach { host ->
                    key(host.id) {
                        if (host.id == lineBeforeId) DropLine()
                        HostRow(host, state, section, controller, sessions, connect, mono, selectedHostId, onSelectHost, dragState, foldersProvider)
                    }
                }
                // Drop at the folder's end: the line goes after the last row.
                if (dropIndex != null && dropIndex == others.size) DropLine()
            }
        }
    }
}
