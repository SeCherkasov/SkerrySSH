package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import app.skerry.ui.design.folderLabel
import app.skerry.ui.design.folderLinePlacement
import app.skerry.ui.host.rowSubtitle
import app.skerry.ui.host.rowLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import app.skerry.ui.terminal.SidebarCatalogRow
import app.skerry.ui.terminal.SidebarDropGeometry
import app.skerry.ui.terminal.sidebarCatalogRows
import app.skerry.ui.terminal.PinSidebarDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection
import app.skerry.ui.host.ProdBadge
import app.skerry.ui.host.isProdHost
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_add_first
import app.skerry.ui.generated.resources.shell_no_hosts_yet
import app.skerry.ui.generated.resources.shell_add_first_host
import app.skerry.ui.generated.resources.rd_no_desktops
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.design.DragFolder
import app.skerry.ui.design.FolderDropLine
import app.skerry.ui.host.asDragFolders
import app.skerry.ui.design.FolderDragState
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.teams.AutoPullTeamsOnOnline
import app.skerry.ui.design.Txt
import app.skerry.ui.design.folderHeaderAnchor
import app.skerry.ui.design.itemBoundsAnchor
import app.skerry.ui.host.icon
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.session.sessionStatusText
import app.skerry.ui.design.draggableFolderHeader
import app.skerry.ui.design.draggableItemRow
import app.skerry.ui.design.visibleItemIds
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/** Preview catalog for the path without a live [LocalHosts] (offscreen/preview). */
internal val MOBILE_PREVIEW_HOSTS = listOf(
    Host("p1", "prod-web-01", "192.168.1.45", 22, "root", "Production"),
    Host("p2", "db-master", "192.168.1.50", 22, "root", "Production"),
    Host("p3", "homelab-pi", "10.0.0.12", 22, "pi", "Homelab"),
    Host("p4", "nas-truenas", "10.0.0.20", 22, "admin", "Homelab"),
)

/** Root screen of the Hosts tab: the terminal-style half of the catalog. */
@Composable
fun MobileHostsScreen(state: MobileDesignState) = MobileCatalogScreen(state, HostSection.Terminal)

/** Root screen of the Desktops tab: remote desktops, the same list over the other half of the catalog. */
@Composable
fun MobileDesktopsScreen(state: MobileDesignState) = MobileCatalogScreen(state, HostSection.RemoteDesktops)

/**
 * One catalog screen, shown once per [section] (Hosts / Desktops): header with title and sync
 * indicator, search field, tag filter-chip row, folder sections, and a "new connection" FAB that
 * opens the form on this section's protocols. Catalog is the live [LocalHosts] (behind the vault
 * gate) or [MOBILE_PREVIEW_HOSTS] on the preview path, narrowed to [section]. Tapping a host opens
 * [MobileRoute.HostDetail].
 */
@Composable
private fun MobileCatalogScreen(state: MobileDesignState, section: HostSection) {
    val controller = LocalHosts.current
    val allHosts = controller?.hosts ?: MOBILE_PREVIEW_HOSTS
    // Memoized like the desktop sidebar's slice: the filter would otherwise rerun on every
    // recomposition (every drag frame) over the whole catalog.
    val hosts = remember(allHosts, section) { allHosts.inSection(section) }
    // Pulls shared team hosts when sync goes Online (see AutoPullTeamsOnOnline): the screen is
    // recreated on tab selection (MobileDesignApp `when(tab)`), so the effect runs on every entry,
    // keyed on Online so it fires once per connection.
    AutoPullTeamsOnOnline()
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    val list = remember(hosts, query, chip) { buildMobileHostList(hosts, query, chip) }
    // Manual reorder state (touch DnD): the gesture reports the target, the controller commits the move.
    // Shared core with desktop ([FolderDragState] + pure geometry [itemDropTarget]/[folderDropTarget]).
    val dragState = remember { FolderDragState() }
    // Fresh folder list for drag targets: the gesture reads it at drop time, not at gesture start.
    val dragFolders = rememberUpdatedState(remember(list) { list.sections.asDragFolders() })

    val rows = remember(list, state.collapsedGroups, dragState.draggingFolderName != null) {
        sidebarCatalogRows(list.sections, state::isGroupCollapsed, dragState.draggingFolderName != null,
            groupUngroupedByTransport = false)
    }
    val foldersProvider = remember { { dragFolders.value } }
    val listState = rememberLazyListState()
    val windowTop = remember { floatArrayOf(0f) }
    val geometry = remember(list, rows, listState) {
        SidebarDropGeometry(list.sections, rows, listState).also { it.windowTop = windowTop[0] }
    }
    SideEffect {
        dragState.itemDropProvider = geometry::itemDrop
        dragState.folderDropProvider = geometry::folderDrop
    }
    DisposableEffect(dragState) {
        onDispose { dragState.itemDropProvider = null; dragState.folderDropProvider = null; dragState.endDrag() }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect {
            if (dragState.draggingItemId != null) dragState.refreshDrop(dragFolders.value)
            if (dragState.draggingFolderName != null) dragState.refreshFolderDrop(dragFolders.value)
        }
    }
    val folderLine = dragState.folderLinePlacement(list.sections.map { it.name })
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned {
            windowTop[0] = it.positionInWindow().y
            geometry.windowTop = windowTop[0]
        }, state = listState) {
            item(key = "catalog-title") { HostsHeader(section) }
            item(key = "catalog-search") { HostsSearch(query, section, onChange = { query = it }) }
            item(key = "catalog-chips") { HostsChips(list.chips, active = chip, onSelect = { chip = it }) }
            item(key = "catalog-gap") { Spacer(Modifier.height(2.dp)) }
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is SidebarCatalogRow.Header -> {
                        PinSidebarDrag(dragState.draggingFolderName == row.folder.name)
                        DisposableEffect(row.folder.name) { onDispose { dragState.clearFolderBounds(row.folder.name) } }
                        Column {
                            if (row.folder.name == folderLine.before) FolderDropLine()
                            MobileHostFolderHeader(row.folder, state, controller, dragState, foldersProvider)
                        }
                    }
                    is SidebarCatalogRow.Entry -> {
                        PinSidebarDrag(dragState.draggingItemId == row.host.id)
                        val folder = list.sections[row.folderIndex]
                        val draggedIndex = geometry.draggedIndex(dragState.draggingItemId, row.folderIndex)
                        val index = row.index - if (draggedIndex in 0 until row.index) 1 else 0
                        val target = dragState.draggingItemId != null && dragState.activeDrop?.group == row.host.group
                        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 2.dp)) {
                            if (target && row.host.id != dragState.draggingItemId && dragState.activeDrop?.index == index)
                                FolderDropLine(horizontal = 0.dp)
                            MobileDraggableHostRow(row.host, state, controller, dragState, foldersProvider)
                            if (target && row.index == folder.hosts.lastIndex && dragState.activeDrop?.index ==
                                folder.hosts.size - (if (draggedIndex >= 0) 1 else 0)) FolderDropLine(horizontal = 0.dp)
                        }
                    }
                    is SidebarCatalogRow.Transport -> Unit // The mobile catalog has no transport subheaders.
                }
            }
            if (folderLine.atEnd) item(key = "folder-end-line") { FolderDropLine() }
            if (query.isBlank() && chip == ALL_HOSTS_CHIP) item(key = "team-catalog") {
                MobileTeamHostsSections(hosts, section)
            }
            if (list.sections.isEmpty() && query.isBlank() && chip == ALL_HOSTS_CHIP) item(key = "empty-catalog") {
                MobileEmptyCatalogNote(section)
            }
            // Keep the last rows clear of the tab bar and the FAB.
            item(key = "catalog-bottom-space") { Spacer(Modifier.height(176.dp)) }
        }
        MobileFabButton(
            onClick = { state.openNewConn(section) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp).testTag(UiTags.NEW_CONNECTION),
        )
    }
}

/**
 * Collapsible folder header with the same logical drag geometry as the desktop lazy catalog.
 * A collapsed folder hides its rows; live headers can also rename and reorder their group.
 */
@Composable
private fun MobileHostFolderHeader(
    folder: HostFolder,
    state: MobileDesignState,
    controller: HostManagerController?,
    dragState: FolderDragState,
    foldersProvider: () -> List<DragFolder>,
) {
    val group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL }
    val collapsed = state.isGroupCollapsed(folder.name)
    val onToggle = remember(state, folder.name) { { state.toggleGroupCollapsed(folder.name) } }
    val onEdit = remember(state, folder.name) { { state.openRenameGroup(folder.name) } }
        .takeIf { controller != null && folder.name != UNGROUPED_LABEL }
    val dragging = dragState.draggingFolderName == folder.name
    val isDropTarget = dragState.draggingItemId != null && dragState.activeDrop?.group == group
    val headerMod = if (controller != null) {
        Modifier.folderHeaderAnchor(dragState, folder.name)
            .draggableFolderHeader(dragState, folder.name, foldersProvider, longPress = true) { index ->
                controller.moveFolderInSection(group, index, foldersProvider().visibleItemIds())
            }
    } else Modifier
    Column(Modifier.alpha(if (dragging) 0.6f else 1f)) {
        Box(headerMod) {
            val title = if (folder.name == UNGROUPED_LABEL) ungroupedLabel() else folderLabel(folder.name)
            MobileFolderHeader(title, folder.hosts.size, collapsed, isDropTarget, onToggle, onEdit, isDragging = dragging)
        }
        if (isDropTarget && collapsed) FolderDropLine()
    }
}

@Composable
private fun MobileDraggableHostRow(
    host: Host,
    state: MobileDesignState,
    controller: HostManagerController?,
    dragState: FolderDragState,
    foldersProvider: () -> List<DragFolder>,
) {
    DisposableEffect(host.id) { onDispose { dragState.clearItemBounds(host.id) } }
    val onOpen = remember(host.id, state) { { state.openHost(host.id) } }
    val rowMod = if (controller != null) {
        Modifier.alpha(if (dragState.draggingItemId == host.id) 0.4f else 1f)
            .itemBoundsAnchor(dragState, host.id)
            .draggableItemRow(dragState, host.id, foldersProvider, longPress = true) { drop ->
                controller.moveHostInSection(host.id, drop.group, drop.index, foldersProvider().visibleItemIds())
            }
    } else Modifier
    Box(rowMod) { MobileHostRow(host, onClick = onOpen) }
}

/**
 * Note shown when a section's catalog is empty (before any filtering): an empty screen under a lone
 * "+" button doesn't say whether anything was hidden by a filter or never created.
 */
@Composable
private fun MobileEmptyCatalogNote(section: HostSection) {
    val title = when (section) {
        HostSection.Terminal -> Res.string.shell_no_hosts_yet
        HostSection.RemoteDesktops -> Res.string.rd_no_desktops
    }
    val subtitle = when (section) {
        HostSection.Terminal -> Res.string.shell_add_first_host
        HostSection.RemoteDesktops -> Res.string.rd_add_first
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(stringResource(title), color = Skerry.colors.dim, size = 14.sp)
        Txt(stringResource(subtitle), color = Skerry.colors.faint, size = 12.5.sp, lineHeight = 18.sp)
    }
}

/**
 * Host row, per the mobile template: a flat line — icon tile, monospace label over a monospace
 * `user@address:port`, status dot at the right edge. No card and no border; the list is separated by
 * whitespace, and the tile is the only filled shape (a frame around every profile turned the
 * catalog into a stack of boxes and left the eye nothing to follow).
 *
 * The tile carries the profile's protocol ([app.skerry.ui.host.icon], same symbol as the desktop
 * sidebar and the connection form); the dot color is live, taken from the host's latest session
 * status ([SessionsController.sessionStatusFor]) via the desktop-shared [sessionDotColor] (live →
 * green, connecting → amber, error/dropped → sunset, no session → dim). Reading uiState inside the
 * composition subscribes the row to status changes so the dot updates on connect.
 *
 * A production host keeps its [ProdBadge] beside the label: the template models no such host, and
 * the badge is the marking that survives losing the frame the red outline used to live on.
 */
@Composable
private fun MobileHostRow(host: Host, onClick: () -> Unit) {
    val status = LocalSessions.current?.sessionStatusFor(host.id) ?: SessionStatus.Idle
    MobileCatalogRow(
        icon = host.connectionType.icon,
        label = remember(host) { host.rowLabel() },
        subtitle = remember(host) { host.rowSubtitle() },
        dotColor = sessionDotColor(status),
        statusText = sessionStatusText(status),
        onClick = onClick,
        badge = if (remember(host) { isProdHost(host) }) ({ ProdBadge() }) else null,
    )
}

