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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.shared.ssh.isRemoteDesktop
import app.skerry.ui.app.LocalTeams
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.host.asTeamShared
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamScopeRef
import androidx.compose.runtime.collectAsState
import app.skerry.ui.design.DragFolder
import app.skerry.ui.design.folderLabel
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.design.SHORT_ID_CHARS
import app.skerry.ui.teams.AutoPullTeamsOnOnline
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.spaceLabel
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_unnamed_space
import app.skerry.ui.generated.resources.rd_add_first
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.generated.resources.shtail_group_rename
import app.skerry.ui.generated.resources.shtail_group_expand
import app.skerry.ui.generated.resources.rd_no_desktops
import app.skerry.ui.generated.resources.term_recent_section
import app.skerry.ui.design.FolderDragState
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostGroup
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection
import app.skerry.ui.host.MockHost
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.color
import app.skerry.ui.design.draggableFolderHeader
import app.skerry.ui.design.folderHeaderAnchor
import app.skerry.ui.design.folderRangeAnchor
import app.skerry.ui.design.visibleItemIds
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
        FolderCollapseToggle(name, collapsed, onToggle)
        Sym("folder_open", size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(name, color = Skerry.colors.dim, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // Rename/delete the group (live catalog only, not for the synthetic "Ungrouped").
        if (onEdit != null) {
            IconBtn(
                "edit",
                onClick = onEdit,
                box = 20,
                icon = 13.sp,
                tint = Skerry.colors.faint,
                tooltip = stringResource(Res.string.shtail_group_rename, name),
            )
        }
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.card).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
        }
    }
}

/**
 * The chevron folding a folder away. Icon-only, so it names itself after what it does and to which
 * folder — a list of headers all saying "Collapse" says nothing about which one is which.
 */
@Composable
private fun FolderCollapseToggle(name: String, collapsed: Boolean, onToggle: () -> Unit) {
    val label = stringResource(
        if (collapsed) Res.string.shtail_group_expand else Res.string.shtail_group_collapse,
        name,
    )
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).handsKeyboardBack().clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Sym(
            if (collapsed) "chevron_right" else "expand_more",
            size = 16.sp,
            color = Skerry.colors.faint,
            contentDescription = label,
        )
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
internal fun TeamHostsSectionHeader() {
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
internal fun rememberTeamSections(hostsSnapshot: List<Host>, section: HostSection): List<TeamSection> {
    val teams = LocalTeams.current ?: return emptyList()
    // Pulls shared team hosts when sync transitions to Online, see AutoPullTeamsOnOnline.
    AutoPullTeamsOnOnline()
    val teamList by teams.teams.collectAsState()
    // Changes on every team sync, so hosts freshly pulled into the team vault appear without a
    // manual sync (the personal catalog doesn't change, and these sections read the vault directly).
    val revision by teams.revision.collectAsState()
    // Resolved outside the remember (stringResource is composable-only) and keyed into it: the name AND
    // the id can both filter away to nothing, and an unlabelled fold header is one the user cannot tell
    // from any other — same last resort the device list has.
    val unnamed = stringResource(Res.string.lib_teams_unnamed_space)
    val sections = remember(teamList, hostsSnapshot, revision, section, unnamed) {
        teamList.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.flatMap { team ->
            // One group per share space: the team itself, plus every scope whose key we hold. A scope
            // we're merely told about (manager without a grant) has no readable records, so no group.
            val teamName = spaceLabel(team.name, fallback = untrustedLabel(team.id).take(SHORT_ID_CHARS))
                .ifBlank { unnamed }
            val spaces = listOf(TeamScopeRef(team.id) to teamName) +
                team.scopes.filter { it.hasKey }.map {
                    TeamScopeRef(team.id, it.id) to
                        "$teamName · ${spaceLabel(it.name, fallback = untrustedLabel(it.id).take(SHORT_ID_CHARS)).ifBlank { unnamed }}"
                }
            spaces.mapNotNull { (ref, label) ->
                val vault = teams.spaceVault(ref) ?: return@mapNotNull null
                // Shared hosts are split by section like the personal catalog: a team's VNC box belongs
                // to the desktops list, its servers to the terminal one.
                val shared = VaultHostStore(vault).all().map(Host::asTeamShared).inSection(section)
                if (shared.isEmpty()) null else TeamSection(ref, label, shared)
            }
        }
    }
    return sections
}

/**
 * One shared space in the sidebar: what it is called, what it holds, and what its fold state is
 * filed under. The label and the key are deliberately different things — see [spaceLabel].
 */
internal class TeamSection(ref: TeamScopeRef, val label: String, val hosts: List<Host>) {
    val collapseKey: String = "$TEAM_COLLAPSE_PREFIX${ref.teamId}\u0000${ref.scopeId.orEmpty()}"
}

/** Collapse-key prefix for teams in the shared [DesktopDesignState.collapsedGroups], see [rememberTeamSections]. */
private const val TEAM_COLLAPSE_PREFIX = "\u0000team\u0000"

/**
 * Team header in the sidebar, modeled on [FolderHeader] (collapse chevron + icon + name + count) to
 * visually match host folders; differs only in the `group` icon marking its team-vault origin.
 */
@Composable
internal fun TeamFolderHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FolderCollapseToggle(name, collapsed, onToggle)
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
 * A live catalog folder's header, kept separate from its lazy host rows. Dragging this node
 * temporarily hides rows while retaining the keyed header and its pointer gesture.
 * Drops commit through [controller]; [foldersProvider] supplies the current filtered folder list.
 */
@Composable
internal fun LiveHostFolderHeader(
    folder: HostFolder,
    state: DesktopDesignState,
    dragState: FolderDragState,
    controller: HostManagerController,
    foldersProvider: () -> List<DragFolder>,
) {
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
    val isThisFolderDragging = dragState.draggingFolderName == folder.name
    // Highlights the target folder while a host is dragged over it.
    val isDropTarget = dragState.draggingItemId != null && dragState.activeDrop?.group == group
    val folderAlpha = if (isThisFolderDragging) 0.6f else 1f
    Column(
        Modifier
            .padding(bottom = 2.dp)
            .alpha(folderAlpha)
            .folderRangeAnchor(dragState, folder.name),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        isThisFolderDragging -> Skerry.colors.card
                        isDropTarget -> Skerry.colors.cyan.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    }
                )
                .border(
                    1.dp,
                    when {
                        isThisFolderDragging -> Skerry.colors.cyan
                        isDropTarget -> Skerry.colors.cyanBright
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(6.dp)
                )
                .folderHeaderAnchor(dragState, folder.name)
                // The drop index counts the folders this sidebar shows, not the catalog's: one the
                // other section, the search or the chip left out is invisible here and keeps its
                // place, so the rows on screen travel with the index.
                .draggableFolderHeader(
                    state = dragState,
                    name = folder.name,
                    folders = foldersProvider,
                ) { index ->
                    controller.moveFolderInSection(group, index, foldersProvider().visibleItemIds())
                },
        ) {
            // The synthetic bucket shows the localized "no group" label; a real folder shows its own
            // name, filtered the way every other folder header filters it ([folderLabel]) — a group
            // name can arrive over sync from a client that never normalized it.
            val headerName = if (folder.name == UNGROUPED_LABEL) ungroupedLabel() else folderLabel(folder.name)
            FolderHeader(headerName, folder.hosts.size, collapsed, onToggleCollapsed, onEditGroup)
        }
    }
}
