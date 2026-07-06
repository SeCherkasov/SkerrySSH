package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalRunSnippetOnHost
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTeams
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.team.TeamMemberStatus
import androidx.compose.runtime.collectAsState
import app.skerry.ui.teams.AutoPullTeamsOnOnline
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.design.Badge
import app.skerry.ui.design.Chip
import app.skerry.ui.design.D
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_hosts_section
import app.skerry.ui.generated.resources.term_menu_delete
import app.skerry.ui.generated.resources.term_menu_edit
import app.skerry.ui.generated.resources.term_menu_run_snippet
import app.skerry.ui.generated.resources.term_new_connection
import app.skerry.ui.generated.resources.term_no_hosts_match
import app.skerry.ui.generated.resources.term_recent_section
import app.skerry.ui.generated.resources.term_search_hosts_placeholder
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.HOST_GROUPS
import app.skerry.ui.host.HostDragState
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostGroup
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.MockHost
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.draggableHostRow
import app.skerry.ui.host.filterHosts
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.host.hostBoundsAnchor
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.host.hostTagChips
import app.skerry.ui.session.sessionDotColor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Terminal view host sidebar: search, tag filters, catalog folders (live drag-and-drop or mock
// preview), a RECENT section, and the "New connection" button.

@Composable
internal fun HostsSidebar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val liveHosts = LocalHosts.current
    // Manual reorder (drag-and-drop) state for the live catalog; unused on the mock path.
    val dragState = remember { HostDragState() }
    // Active filter chip (tag); live catalog only, chips are static on the mock path.
    var activeChip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    val chips = liveHosts?.let { remember(it.hosts) { hostTagChips(it.hosts) } } ?: emptyList()
    // If the active tag disappears (host edited/deleted), the filter falls back to "All".
    val effectiveChip = if (activeChip in chips) activeChip else ALL_HOSTS_CHIP
    Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            // Collapse-sidebar button (left) plus search. Expansion uses the same button on a thin rail;
            // both use box=34 with the same top padding so height doesn't jump when toggling.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconBtn("chevron_left", onClick = state::toggleSidebar, box = 34, tint = D.dim)
                Box(Modifier.weight(1f)) { HostSearchField(state) }
            }
            // The filter-tag row overflows the narrow sidebar, so it scrolls horizontally. Desktop's
            // vertical mouse wheel doesn't translate to horizontal on its own, so Scroll events are
            // caught and [chipScroll] is driven manually (delta.y, or delta.x on a horizontal axis);
            // touch/Android scrolls via horizontalScroll's normal drag.
            val chipScroll = rememberScrollState()
            val chipScope = rememberCoroutineScope()
            Row(
                Modifier
                    .padding(top = 9.dp)
                    .horizontalScroll(chipScroll)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                val d = event.changes.firstOrNull()?.scrollDelta ?: continue
                                val delta = if (d.y != 0f) d.y else d.x
                                if (delta != 0f) {
                                    chipScope.launch { chipScroll.scrollBy(delta * 64f) }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (liveHosts != null) {
                    // Chips are live-catalog tags; clicking switches the filter.
                    chips.forEach { chip ->
                        key(chip) {
                            Chip(hostChipLabel(chip), active = chip == effectiveChip, onClick = { activeChip = chip })
                        }
                    }
                } else {
                    Chip("All", active = true)
                    Chip("#prod"); Chip("#docker"); Chip("#db")
                }
            }
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(stringResource(Res.string.term_hosts_section), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                // Create a new (initially empty) group in the live catalog; decorative on the mock path.
                if (liveHosts != null) {
                    IconBtn("create_new_folder", onClick = state::openCreateGroup, box = 20, icon = 14.sp, tint = D.faint)
                } else {
                    Sym("create_new_folder", size = 14.sp, color = D.faint)
                }
            }
            // Live catalog from HostManagerController when provided (behind the vault gate), otherwise
            // mock data (offscreen render/preview path). Folders are grouped and narrowed by the active tag.
            if (liveHosts != null) {
                val query = state.hostSearchQuery
                val folders = remember(liveHosts.hosts, effectiveChip, query, state.customGroups) {
                    val base = groupHostsByFolder(filterHosts(liveHosts.hosts, effectiveChip, query))
                    // Empty user groups are shown as folders with no hosts, but only outside a filter
                    // (search/tag narrow by host, and an empty folder has nothing to match).
                    if (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP) {
                        base
                    } else {
                        val present = base.map { it.name }.toSet()
                        base + state.customGroups.filter { it !in present }.map { HostFolder(it, emptyList()) }
                    }
                }
                // Search/tag narrowing found nothing: show a hint instead of silent emptiness (unlike an
                // empty catalog, where the RECENT section/New connection button still appear below).
                if (folders.isEmpty() && (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP)) {
                    Txt(
                        stringResource(Res.string.term_no_hosts_match),
                        color = D.faint, size = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
                // Fresh folder list for drag targets (the gesture is keyed to the row/folder key).
                val foldersUpdated = rememberUpdatedState(folders)
                // Insertion line while dragging a folder: before the folder at the target index, or at the end.
                val otherFolders = folders.filter { it.name != dragState.draggingFolderName }
                val folderLineIndex = dragState.draggingFolderName?.let { dragState.activeFolderDropIndex }
                val folderLineBefore = folderLineIndex?.takeIf { it < otherFolders.size }?.let { otherFolders[it].name }
                folders.forEach { folder ->
                    key(folder.name) {
                        if (folder.name == folderLineBefore) DropLine()
                        LiveHostFolder(folder, state, mono, dragState, liveHosts) { foldersUpdated.value }
                    }
                }
                if (folderLineIndex != null && folderLineIndex == otherFolders.size) DropLine()
                // Shared team hosts: per-team sections below the personal catalog, shown only outside
                // search/filter since those narrow the personal catalog.
                if (query.isBlank() && effectiveChip == ALL_HOSTS_CHIP) {
                    TeamHostsSection(liveHosts.hosts, state, mono)
                }
            } else {
                HOST_GROUPS.forEach { group -> HostGroupBlock(group, state, mono) }
            }
            // Live catalog: RECENT section from actual connection history ([DesktopDesignState.recentHostIds]),
            // resolved against current profiles; deleted/unknown ids are simply hidden, empty means no section.
            // Mock/preview (no live catalog): a static row.
            if (liveHosts != null) {
                // The section can be hidden entirely (Settings -> Appearance -> Interface) and size-limited.
                // Memoized by (recent order, catalog contents, limit), like the `folders` above, so the
                // resolve doesn't rerun on every sidebar recomposition (drag/chip switch/tab switch).
                val recent = remember(state.recentHostIds, liveHosts.hosts, state.recentLimit) {
                    state.recentHostIds.mapNotNull { liveHosts.find(it) }.take(state.recentLimit)
                }
                if (state.showRecent && recent.isNotEmpty()) {
                    RecentSectionHeader()
                    recent.forEach { host -> key(host.id) { RecentHostRow(host, mono) } }
                }
            } else {
                RecentSectionHeader()
                Row(
                    Modifier.padding(start = 16.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("history", size = 14.sp, color = D.faint)
                    Txt("user@vps.example.com", color = D.dim, size = 11.5.sp, font = mono)
                }
            }
        }
        HLine()
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            PrimaryButton(stringResource(Res.string.term_new_connection), onClick = state::openModal, icon = "add_link", modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Search field for the host sidebar (name/address/user/group/tags). Border/icon/placeholder live
 * in decorationBox so a click anywhere places the caret. Shows a `⌘K` badge when empty, a clear
 * cross once text is entered.
 */
@Composable
private fun HostSearchField(state: DesktopDesignState) {
    val query = state.hostSearchQuery
    BasicTextField(
        value = query,
        onValueChange = state::onHostSearch,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(D.card)
                    .border(1.dp, D.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Txt(stringResource(Res.string.term_search_hosts_placeholder), color = D.faint, size = 12.5.sp)
                    inner()
                }
                if (query.isNotEmpty()) {
                    val onClear = remember(state) { { state.onHostSearch("") } }
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("close", size = 14.sp, color = D.faint)
                    }
                }
            }
        },
    )
}

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
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = D.faint)
        }
        Sym("folder_open", size = 15.sp, color = D.cyanBright)
        Txt(name, color = D.dim, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // Rename/delete the group (live catalog only, not for the synthetic "Ungrouped").
        if (onEdit != null) IconBtn("edit", onClick = onEdit, box = 20, icon = 13.sp, tint = D.faint)
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = D.faint, size = 10.sp)
        }
    }
}

/** RECENT section header in the sidebar (shared by the live and mock paths). */
@Composable
private fun RecentSectionHeader() {
    Txt(
        stringResource(Res.string.term_recent_section),
        color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Recent-connection row: history icon + host name ([Host.label]) with a `user@address` secondary
 * caption below it. Click reconnects via [LocalConnectHost], same path as clicking a catalog row.
 */
@Composable
private fun TeamHostsSectionHeader() {
    Txt(
        stringResource(Res.string.lib_teams_sidebar),
        color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
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
private fun TeamHostsSection(hostsSnapshot: List<Host>, state: DesktopDesignState, mono: FontFamily) {
    val teams = LocalTeams.current ?: return
    // Pulls shared team hosts when sync transitions to Online, see AutoPullTeamsOnOnline.
    AutoPullTeamsOnOnline()
    val teamList by teams.teams.collectAsState()
    // Changes on every team sync, so hosts freshly pulled into the team vault appear without a
    // manual sync (the personal catalog doesn't change, and these sections read the vault directly).
    val revision by teams.revision.collectAsState()
    val sections = remember(teamList, hostsSnapshot, revision) {
        teamList.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.mapNotNull { team ->
            val vault = teams.teamVault(team.id) ?: return@mapNotNull null
            val shared = VaultHostStore(vault).all()
            if (shared.isEmpty()) null else team.name to shared
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
private const val TEAM_COLLAPSE_PREFIX = " team "

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
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = D.faint)
        }
        Sym("group", size = 15.sp, color = D.cyanBright)
        Txt(name, color = D.dim, size = 12.5.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = D.faint, size = 10.sp)
        }
    }
}

/** Shared team host row, like [RecentHostRow] but with a share icon. */
@Composable
private fun TeamHostRow(host: Host, mono: FontFamily) {
    val connect = LocalConnectHost.current
    val onClick = remember(host, connect) { { connect(host) } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("lan", size = 14.sp, color = D.faint)
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = D.dim, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt("${host.username}@${host.address}", color = D.faint, size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecentHostRow(host: Host, mono: FontFamily) {
    val connect = LocalConnectHost.current
    // Stabilizes the lambda on (host, connect), like catalog rows: without remember it would be
    // recreated on every row recomposition.
    val onClick = remember(host, connect) { { connect(host) } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("history", size = 14.sp, color = D.faint)
        Column(Modifier.weight(1f)) {
            Txt(
                host.label,
                color = D.dim, size = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Txt(
                "${host.username}@${host.address}",
                color = D.faint, size = 10.5.sp, font = mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HostGroupBlock(group: HostGroup, state: DesktopDesignState, mono: FontFamily) {
    val collapsed = state.isGroupCollapsed(group.name)
    val onToggleCollapsed = remember(state, group.name) { { state.toggleGroupCollapsed(group.name) } }
    Column(Modifier.padding(bottom = 2.dp)) {
        FolderHeader(group.name, group.hosts.size, collapsed, onToggleCollapsed)
        if (!collapsed) {
            Column(Modifier.padding(start = 22.dp)) {
                group.hosts.forEach { host -> HostRow(host, state, mono) }
            }
        }
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
private fun LiveHostFolder(
    folder: HostFolder,
    state: DesktopDesignState,
    mono: FontFamily,
    dragState: HostDragState,
    controller: HostManagerController,
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
            .border(1.dp, if (isDropTarget) D.cyan else Color.Transparent, RoundedCornerShape(6.dp)),
    ) {
        Box(
            Modifier.folderHeaderAnchor(dragState, folder.name)
                .draggableFolderHeader(dragState, folder.name, foldersProvider) { index ->
                    controller.moveFolder(group, index)
                },
        ) {
            FolderHeader(folder.name, folder.hosts.size, collapsed, onToggleCollapsed, onEditGroup)
        }
        // A collapsed folder shows only the header; the host list (and its drag targets) is hidden.
        if (!collapsed) Column(Modifier.padding(start = 22.dp)) {
            // key(host.id): row positional identity is pinned to the host, so an open menu/row state
            // doesn't jump to a neighbor when the catalog reorders after an edit.
            folder.hosts.forEach { host ->
                key(host.id) {
                    if (host.id == lineBeforeId) DropLine()
                    // Stabilizes lambdas on (host, ...): otherwise every folder recomposition would
                    // recreate them and force the row to redraw (nullable functions are unstable).
                    val onClick = remember(host, connect) { { connect(host) } }
                    val onEdit = remember(host, state) { { state.openEditModal(host) } }
                    val onDelete = remember(host, state) { { state.requestDeleteHost(host) } }
                    // Forgets the row's geometry once the host leaves the list (deleted/filtered out).
                    DisposableEffect(host.id) { onDispose { dragState.clearHostBounds(host.id) } }
                    Box(
                        Modifier
                            .alpha(if (dragState.draggingHostId == host.id) 0.4f else 1f)
                            .hostBoundsAnchor(dragState, host.id)
                            .draggableHostRow(dragState, host.id, foldersProvider) { drop ->
                                controller.moveHost(host.id, drop.group, drop.index)
                            },
                    ) {
                        HostEntryRow(
                            label = host.label,
                            // Active-tab host highlight removed: with a split view two hosts can be
                            // active, so highlighting one would mislead. The status dot on the right
                            // still reflects live connection state.
                            selected = false,
                            dot = sessionDotColor(sessions?.statusFor(host.id)),
                            badge = null,
                            onClick = onClick,
                            mono = mono,
                            // Host object, for the "Run snippet..." menu item (runs a snippet on this host).
                            host = host,
                            // Edit/delete the profile via the context menu (right-click/long-press).
                            onEdit = onEdit,
                            onDelete = onDelete,
                        )
                    }
                }
            }
            // Drop at the folder's end: the line goes after the last row.
            if (dropIndex != null && dropIndex == others.size) DropLine()
        }
    }
}

/** Cyan indicator line marking where a dragged host/folder will be inserted. */
@Composable
private fun DropLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(D.cyan),
    )
}

@Composable
private fun HostRow(host: MockHost, state: DesktopDesignState, mono: FontFamily) {
    HostEntryRow(
        label = host.name,
        selected = state.selectedHost == host.name,
        dot = host.status.color,
        badge = host.badge,
        onClick = { state.selectHost(host.name) },
        mono = mono,
    )
}

/**
 * Shared host row for the sidebar (mock and live catalog): status dot + name + optional badge.
 * Clicking the row connects ([onClick]). When [onEdit]/[onDelete] are provided (live catalog) or a
 * snippet can be run on the host ([host] != null and [LocalSnippets] is present), a trailing "⋮"
 * button opens a menu (Run snippet.../Edit/Delete); its click is intercepted before [onClick], so
 * opening the menu doesn't trigger a connection. "Run snippet..." opens the snippet picker and runs
 * it on [host] via [LocalRunSnippetOnHost].
 */
@Composable
private fun HostEntryRow(
    label: String,
    selected: Boolean,
    dot: Color,
    badge: String?,
    onClick: () -> Unit,
    mono: FontFamily,
    host: Host? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val snippets = LocalSnippets.current
    val runSnippetOnHost = LocalRunSnippetOnHost.current
    val canRunSnippet = host != null && snippets != null
    val hasMenu = onEdit != null || onDelete != null || canRunSnippet
    var menuOpen by remember { mutableStateOf(false) }
    var snippetPickerOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Txt(label, color = if (selected) D.cyanBright else D.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
        if (badge != null) {
            val strict = badge == "STRICT"
            Badge(badge, bg = if (strict) D.strictBg else D.devBg, fg = if (strict) D.strictFg else D.moss)
        }
        if (hasMenu) {
            Box {
                IconBtn("more_vert", onClick = { menuOpen = !menuOpen }, box = 22, icon = 16.sp, tint = D.faint)
                if (menuOpen) {
                    Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                        Column(
                            Modifier.clip(RoundedCornerShape(7.dp)).background(D.surface2).border(1.dp, D.lineStrong, RoundedCornerShape(7.dp)).padding(4.dp),
                        ) {
                            if (canRunSnippet) {
                                HostMenuItem(stringResource(Res.string.term_menu_run_snippet), D.text) { menuOpen = false; snippetPickerOpen = true }
                            }
                            onEdit?.let { edit ->
                                HostMenuItem(stringResource(Res.string.term_menu_edit), D.text) { menuOpen = false; edit() }
                            }
                            onDelete?.let { delete ->
                                HostMenuItem(stringResource(Res.string.term_menu_delete), D.sunset) { menuOpen = false; delete() }
                            }
                        }
                    }
                }
                // Snippet picker: runs on this host (opens/reuses a session and runs the command after
                // connecting). An empty library shows "No snippets yet".
                if (snippetPickerOpen && host != null && snippets != null) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        onDismissRequest = { snippetPickerOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        SnippetPalette(snippets) { entry ->
                            runSnippetOnHost(host, entry.snippet.command)
                            snippetPickerOpen = false
                        }
                    }
                }
            }
        }
    }
}

/** Context menu item for a host row. */
@Composable
private fun HostMenuItem(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(label, color = color, size = 12.sp)
    }
}
