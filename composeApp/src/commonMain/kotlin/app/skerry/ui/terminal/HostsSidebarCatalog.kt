package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.FolderDragState
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Txt
import app.skerry.ui.design.folderLinePlacement
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_hosts_section
import app.skerry.ui.generated.resources.rd_section
import app.skerry.ui.generated.resources.shell_group_new_title
import app.skerry.ui.generated.resources.term_no_hosts_match
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.HOST_GROUPS
import app.skerry.ui.host.MOCK_RECENT_CONNECTION
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.asDragFolders
import app.skerry.ui.host.connectionTypeLabel
import app.skerry.ui.host.filterHosts
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.host.inSection
import app.skerry.ui.host.sidebarFolders
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Each catalog header and host owns a lazy item, including hosts within a single large folder. */
@Composable
internal fun HostsSidebarCatalog(
    state: DesktopDesignState,
    section: HostSection,
    sectionHosts: List<Host>,
    effectiveChip: String,
    controller: HostManagerController?,
    mono: FontFamily,
    dragState: FolderDragState,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    modifier: Modifier,
) {
    val query = state.hostSearchQuery
    val folders = remember(sectionHosts, controller?.hosts, effectiveChip, query, state.customGroups, section) {
        val filtered = filterHosts(sectionHosts, effectiveChip, query)
        if (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP) groupHostsByFolder(filtered)
        else sidebarFolders(filtered, controller?.hosts.orEmpty(), state.customGroupsIn(section))
    }
    val rows = remember(folders, state.collapsedGroups, dragState.draggingFolderName != null) {
        sidebarCatalogRows(folders, state::isGroupCollapsed, dragState.draggingFolderName != null)
    }
    val dragFolders = rememberUpdatedState(remember(folders) { folders.asDragFolders() })
    val foldersProvider = remember { { dragFolders.value } }
    val listState = rememberLazyListState()
    val windowTop = remember { floatArrayOf(0f) }
    val geometry = remember(folders, rows, listState) {
        SidebarDropGeometry(folders, rows, listState).also { it.windowTop = windowTop[0] }
    }
    SideEffect {
        dragState.itemDropProvider = geometry::itemDrop
        dragState.folderDropProvider = geometry::folderDrop
    }
    DisposableEffect(dragState) {
        onDispose { dragState.itemDropProvider = null; dragState.folderDropProvider = null; dragState.endDrag() }
    }
    // A wheel scroll changes pixels without a pointer move. Update the highlighted target too.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect {
            if (dragState.draggingItemId != null) dragState.refreshDrop(dragFolders.value)
            if (dragState.draggingFolderName != null) dragState.refreshFolderDrop(dragFolders.value)
        }
    }
    val teams = if (controller != null && query.isBlank() && effectiveChip == ALL_HOSTS_CHIP)
        rememberTeamSections(controller.hosts, section) else emptyList()
    val recent = remember(state.recentHostIds, controller?.hosts, state.settings.recentLimit, section) {
        state.recentHostIds.mapNotNull { controller?.find(it) }.inSection(section).take(state.settings.recentLimit)
    }
    val sessions = LocalSessions.current
    val connect = LocalConnectHost.current
    val folderNames = remember(folders) { folders.map { it.name } }
    val folderLine = dragState.folderLinePlacement(folderNames)
    LazyColumn(
        modifier.onGloballyPositioned {
            windowTop[0] = it.positionInWindow().y
            geometry.windowTop = windowTop[0]
        },
        state = listState,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    ) {
        item(key = "catalog-title") {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SidebarSectionTitle(stringResource(if (section == HostSection.Terminal) Res.string.term_hosts_section else Res.string.rd_section))
                IconBtn("create_new_folder", onClick = { if (controller != null) state.openCreateGroup(section) },
                    box = 20, icon = 14.sp, tint = Skerry.colors.faint,
                    tooltip = stringResource(Res.string.shell_group_new_title), modifier = Modifier.testTag(UiTags.NEW_GROUP))
            }
        }
        if (controller != null) {
            if (folders.isEmpty()) item(key = "empty-catalog") {
                if (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP) {
                    Txt(stringResource(Res.string.term_no_hosts_match), color = Skerry.colors.faint, size = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp))
                } else if (section == HostSection.RemoteDesktops) EmptyCatalogNote()
            }
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is SidebarCatalogRow.Header -> {
                        PinSidebarDrag(dragState.draggingFolderName == row.folder.name)
                        DisposableEffect(row.folder.name) { onDispose { dragState.clearFolderBounds(row.folder.name) } }
                        Column {
                            if (folderLine.before == row.folder.name) DropLine()
                            LiveHostFolderHeader(row.folder, state, dragState, controller, foldersProvider)
                            if (dragState.draggingItemId != null && dragState.activeDrop?.group ==
                                (row.folder.hosts.firstOrNull()?.group ?: row.folder.name.takeIf { it != UNGROUPED_LABEL }) &&
                                state.isGroupCollapsed(row.folder.name)) DropLine()
                        }
                    }
                    is SidebarCatalogRow.Transport -> Box(Modifier.padding(start = 22.dp)) {
                        HostTypeSubheader(connectionTypeLabel(row.type))
                    }
                    is SidebarCatalogRow.Entry -> {
                        PinSidebarDrag(dragState.draggingItemId == row.host.id)
                        val folder = folders[row.folderIndex]
                        val draggedIndex = geometry.draggedIndex(dragState.draggingItemId, row.folderIndex)
                        val index = row.index - if (draggedIndex in 0 until row.index) 1 else 0
                        val target = dragState.draggingItemId != null && dragState.activeDrop?.group == row.host.group && folder.name != UNGROUPED_LABEL
                        Column(Modifier.padding(start = 22.dp)) {
                            if (target && row.host.id != dragState.draggingItemId && dragState.activeDrop?.index == index) DropLine()
                            HostRow(row.host, state, controller, sessions, connect, mono, selectedHostId, onSelectHost, dragState, foldersProvider)
                            if (target && row.index == folder.hosts.lastIndex && dragState.activeDrop?.index ==
                                folder.hosts.size - (if (draggedIndex >= 0) 1 else 0)) DropLine()
                        }
                    }
                }
            }
            if (folderLine.atEnd) item(key = "folder-end-line") { DropLine() }
            if (teams.isNotEmpty()) item(key = "teams-title") { TeamHostsSectionHeader() }
            teams.forEach { team ->
                item(key = "team-header:${team.collapseKey}") {
                    TeamFolderHeader(team.label, team.hosts.size, state.isGroupCollapsed(team.collapseKey)) {
                        state.toggleGroupCollapsed(team.collapseKey)
                    }
                }
                if (!state.isGroupCollapsed(team.collapseKey)) items(team.hosts, key = { "team:${team.collapseKey}:${it.id}" }) {
                    TeamHostRow(it, mono)
                }
            }
            if (state.settings.showRecent && recent.isNotEmpty()) {
                item(key = "recent-title") { HLine(modifier = Modifier.padding(top = 8.dp)); RecentSectionHeader() }
                items(recent, key = { "recent:${it.id}" }) { RecentHostRow(it, mono) }
            }
        } else {
            items(HOST_GROUPS, key = { "mock:${it.name}" }) { HostGroupBlock(it, state, section, mono) }
            item(key = "mock-recent") {
                HLine(modifier = Modifier.padding(top = 8.dp)); RecentSectionHeader()
                Row(Modifier.padding(start = 16.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.skerry.ui.design.Sym("history", size = 14.sp, color = Skerry.colors.faint)
                    Txt(MOCK_RECENT_CONNECTION, color = Skerry.colors.dim, size = 11.5.sp, font = mono)
                }
            }
        }
    }
}

/** Retain the gesture node while wheel scrolling carries its lazy item outside the viewport. */
@Composable
private fun PinSidebarDrag(dragging: Boolean) {
    val container = LocalPinnableContainer.current
    DisposableEffect(container, dragging) {
        val pin = if (dragging) container?.pin() else null
        onDispose { pin?.release() }
    }
}
