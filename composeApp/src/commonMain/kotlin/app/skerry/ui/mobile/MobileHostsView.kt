package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import app.skerry.ui.host.rowSubtitle
import app.skerry.ui.host.rowLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
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
import app.skerry.ui.host.HostDragState
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.teams.AutoPullTeamsOnOnline
import app.skerry.ui.design.Txt
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.hostBoundsAnchor
import app.skerry.ui.host.icon
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.session.sessionStatusText
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.draggableHostRow
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
    // Shared core with desktop ([HostDragState] + pure geometry [hostDropTarget]/[folderDropTarget]).
    val dragState = remember { HostDragState() }
    // Fresh folder list for drag targets: the gesture reads it at drop time, not at gesture start.
    val foldersUpdated = rememberUpdatedState(list.sections)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HostsHeader(section)
            HostsSearch(query, section, onChange = { query = it })
            HostsChips(list.chips, active = chip, onSelect = { chip = it })
            Spacer(Modifier.height(2.dp))
            // Insertion line while dragging a folder: before the folder at the target index (or at the end).
            val otherFolders = list.sections.filter { it.name != dragState.draggingFolderName }
            val folderLineIndex = dragState.draggingFolderName?.let { dragState.activeFolderDropIndex }
            val folderLineBefore = folderLineIndex?.takeIf { it < otherFolders.size }?.let { otherFolders[it].name }
            list.sections.forEach { folder ->
                key(folder.name) {
                    if (folder.name == folderLineBefore) MobileDropLine()
                    MobileHostFolder(folder, state, section, controller, dragState) { foldersUpdated.value }
                }
            }
            if (folderLineIndex != null && folderLineIndex == otherFolders.size) MobileDropLine()
            // Shared team hosts (Teams): sections below the personal catalog, outside search/filter
            // (parity with the desktop sidebar). Tap connects directly (LocalConnectHost).
            if (query.isBlank() && chip == ALL_HOSTS_CHIP) {
                MobileTeamHostsSections(hosts, section)
            }
            // An empty catalog says so rather than leaving a blank screen under the FAB.
            if (list.sections.isEmpty() && query.isBlank() && chip == ALL_HOSTS_CHIP) {
                MobileEmptyCatalogNote(section)
            }
            // Room for the tab bar AND the FAB above it (bottom 104dp + 56dp size + 16dp margin): anything less
            // leaves the last rows permanently stuck under the "+" button at full scroll.
            Spacer(Modifier.height(176.dp))
        }
        MobileFabButton(
            onClick = { state.openNewConn(section) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp).testTag(UiTags.NEW_CONNECTION),
        )
    }
}

/**
 * Host folder: collapsible header (chevron) + row list, both draggable for manual reordering.
 * Drag and insertion lines are active only in the live catalog ([controller] != null) — nothing
 * to sort/persist in preview (mock hosts). A collapsed folder hides its host list (and drag targets).
 */
@Composable
private fun MobileHostFolder(
    folder: HostFolder,
    state: MobileDesignState,
    section: HostSection,
    controller: HostManagerController?,
    dragState: HostDragState,
    foldersProvider: () -> List<HostFolder>,
) {
    // Folder group key: for an empty folder use its name (like FolderBounds), otherwise the first
    // host's group. The synthetic "Ungrouped" folder is the null group.
    val group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL }
    val collapsed = state.isGroupCollapsed(folder.name)
    val onToggle = remember(state, folder.name) { { state.toggleGroupCollapsed(folder.name) } }
    // Edit pencil in the header: only in the live catalog and not for the synthetic "Ungrouped"
    // bucket (can't be renamed). Parity with desktop `LiveHostFolder`. remember is unconditional
    // (stable slot-table position); takeIf controls pencil visibility.
    val onEdit = remember(state, folder.name) { { state.openRenameGroup(folder.name) } }
        .takeIf { controller != null && folder.name != UNGROUPED_LABEL }
    val isAnyFolderDragging = dragState.draggingFolderName != null
    val isThisFolderDragging = dragState.draggingFolderName == folder.name
    // Highlights the target folder while a host is dragged over it.
    val isDropTarget = dragState.draggingHostId != null && dragState.activeHostDrop?.group == group
    val folderAlpha = if (isThisFolderDragging) 0.6f else 1f
    // Insertion line index within the folder, excluding the dragged host (like moveHostToGroup).
    val others = folder.hosts.filter { it.id != dragState.draggingHostId }
    val dropIndex = if (isDropTarget) dragState.activeHostDrop?.index?.coerceIn(0, others.size) else null
    val lineBeforeId = dropIndex?.takeIf { it < others.size }?.let { others[it].id }
    Column(
        Modifier
            .alpha(folderAlpha)
            .let { if (controller != null) it.folderRangeAnchor(dragState, folder.name) else it },
    ) {
        val headerMod = if (controller != null) {
            Modifier
                .folderHeaderAnchor(dragState, folder.name)
                // Section-aware, like desktop: the index counts only the folders on screen.
                .draggableFolderHeader(
                    state = dragState,
                    name = folder.name,
                    folders = foldersProvider,
                    longPress = true,
                ) { index ->
                    controller.moveFolderInSection(group, index, section)
                }
        } else {
            Modifier
        }
        Box(headerMod) {
            // folder.name is a stable key (drag/collapse); the ungrouped bucket shows a localized
            // label while keeping the key technical ([UNGROUPED_LABEL]).
            val folderTitle = if (folder.name == UNGROUPED_LABEL) ungroupedLabel() else folder.name
            MobileFolderHeader(folderTitle, folder.hosts.size, collapsed, isDropTarget, onToggle, onEdit, isDragging = isThisFolderDragging)
        }
        // A collapsed folder shows only its header; when any folder is dragged, all folders
        // temporarily collapse to allow fast, compact and predictable folder reordering.
        if (!collapsed && !isAnyFolderDragging) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                folder.hosts.forEach { host ->
                    key(host.id) {
                        if (host.id == lineBeforeId) MobileDropLine(horizontal = 0.dp)
                        // Drops row geometry when the host leaves the list (move/filter).
                        // clearHostBounds is a no-op safe map.remove even without drag, so the effect is unconditional, as on desktop.
                        DisposableEffect(host.id) { onDispose { dragState.clearHostBounds(host.id) } }
                        // The open lambda is stabilized: every drag frame changes draggingHostId/activeHostDrop
                        // and recomposes the folder — without remember the lambda would be recreated and jitter the row.
                        val onOpen = remember(host.id, state) { { state.openHost(host.id) } }
                        val rowMod = if (controller != null) {
                            Modifier
                                .alpha(if (dragState.draggingHostId == host.id) 0.4f else 1f)
                                .hostBoundsAnchor(dragState, host.id)
                                .draggableHostRow(dragState, host.id, foldersProvider, longPress = true) { drop ->
                                    controller.moveHostInSection(host.id, drop.group, drop.index, section)
                                }
                        } else {
                            Modifier
                        }
                        Box(rowMod) {
                            MobileHostRow(host, onClick = onOpen)
                        }
                    }
                }
                // Drop at folder end: line after the last row.
                if (dropIndex != null && dropIndex == others.size) MobileDropLine(horizontal = 0.dp)
            }
        }
    }
}

/**
 * Cyan line marking where a dragged host/folder will be inserted (parity with desktop).
 * [horizontal] is the side inset: 18dp at the folder level (outer column has no padding), 0dp
 * inside the host column (which already applies `padding(horizontal = 18.dp)`, otherwise the line
 * would be half the width of the rows).
 */
@Composable
private fun MobileDropLine(horizontal: Dp = 18.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Skerry.colors.cyanBright)
        )
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Skerry.colors.cyan)
        )
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Skerry.colors.cyanBright)
        )
    }
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

