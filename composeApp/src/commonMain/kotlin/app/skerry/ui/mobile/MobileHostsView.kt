package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.collectAsState
import app.skerry.ui.sync.SyncIndicatorLevel
import app.skerry.ui.sync.syncIndicatorLocalized
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_hosts
import app.skerry.ui.generated.resources.shell_search_hosts
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.HostDragState
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.teams.AutoPullTeamsOnOnline
import app.skerry.ui.app.MobileTab
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.hostBoundsAnchor
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.host.icon
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.draggableHostRow
import app.skerry.ui.theme.Skerry

/** Preview catalog for the path without a live [LocalHosts] (offscreen/preview). */
internal val MOBILE_PREVIEW_HOSTS = listOf(
    Host("p1", "prod-web-01", "192.168.1.45", 22, "root", "Production"),
    Host("p2", "db-master", "192.168.1.50", 22, "root", "Production"),
    Host("p3", "homelab-pi", "10.0.0.12", 22, "pi", "Homelab"),
    Host("p4", "nas-truenas", "10.0.0.20", 22, "admin", "Homelab"),
)

/**
 * Root screen of the Hosts tab: header with title and avatar (→ More), search field, tag
 * filter-chip row, host sections, and "new connection" FAB. Catalog is the live [LocalHosts]
 * (behind the vault gate) or [MOBILE_PREVIEW_HOSTS] on the preview path. Tapping a host opens
 * [MobileRoute.HostDetail].
 */
@Composable
fun MobileHostsScreen(state: MobileDesignState) {
    val controller = LocalHosts.current
    val hosts = controller?.hosts ?: MOBILE_PREVIEW_HOSTS
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
            HostsHeader(onAvatar = { state.select(MobileTab.More) })
            HostsSearch(query, onChange = { query = it })
            HostsChips(list.chips, active = chip, onSelect = { chip = it })
            Spacer(Modifier.height(2.dp))
            // Insertion line while dragging a folder: before the folder at the target index (or at the end).
            val otherFolders = list.sections.filter { it.name != dragState.draggingFolderName }
            val folderLineIndex = dragState.draggingFolderName?.let { dragState.activeFolderDropIndex }
            val folderLineBefore = folderLineIndex?.takeIf { it < otherFolders.size }?.let { otherFolders[it].name }
            list.sections.forEach { folder ->
                key(folder.name) {
                    if (folder.name == folderLineBefore) MobileDropLine()
                    MobileHostFolder(folder, state, controller, dragState) { foldersUpdated.value }
                }
            }
            if (folderLineIndex != null && folderLineIndex == otherFolders.size) MobileDropLine()
            // Shared team hosts (Teams): sections below the personal catalog, outside search/filter
            // (parity with the desktop sidebar). Tap connects directly (LocalConnectHost).
            if (query.isBlank() && chip == ALL_HOSTS_CHIP) {
                MobileTeamHostsSections(hosts)
            }
            Spacer(Modifier.height(96.dp)) // room for the tab bar and FAB
        }
        MobileFabButton(
            onClick = state::openNewConn,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
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
    // Highlights the target folder while a host is dragged over it.
    val isDropTarget = dragState.draggingHostId != null && dragState.activeHostDrop?.group == group
    val folderAlpha = if (dragState.draggingFolderName == folder.name) 0.4f else 1f
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
                .draggableFolderHeader(dragState, folder.name, foldersProvider, longPress = true) { index ->
                    controller.moveFolder(group, index)
                }
        } else {
            Modifier
        }
        Box(headerMod) {
            // folder.name is a stable key (drag/collapse); the ungrouped bucket shows a localized
            // label while keeping the key technical ([UNGROUPED_LABEL]).
            val folderTitle = if (folder.name == UNGROUPED_LABEL) ungroupedLabel() else folder.name
            MobileFolderHeader(folderTitle, folder.hosts.size, collapsed, isDropTarget, onToggle, onEdit)
        }
        // A collapsed folder shows only its header; the host list (and its drag targets) is hidden.
        if (!collapsed) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
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
                                    controller.moveHost(host.id, drop.group, drop.index)
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal, vertical = 3.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Skerry.colors.cyan),
    )
}

/** Header: "Hosts" (28sp) + round account avatar on the right (opens the More tab). */
@Composable
private fun HostsHeader(onAvatar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MobileScreenTitle(stringResource(Res.string.shell_hosts))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Sync indicator driven by session status (see syncIndicator), not just server
            // reachability: shows "paused/error" without a working session instead of a false green online.
            val syncC = LocalSync.current
            val ind = syncC?.let { syncIndicatorLocalized(it.status.collectAsState().value, it.serverReachable.collectAsState().value) }
            if (ind != null) {
                Sym(ind.icon, size = 19.sp, color = when (ind.level) {
                    SyncIndicatorLevel.OK -> Skerry.colors.moss
                    SyncIndicatorLevel.WARN -> Skerry.colors.amber
                    SyncIndicatorLevel.ERROR -> Skerry.colors.sunset
                })
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Skerry.colors.cyan).clickable(onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) {
                Sym("person", size = 19.sp, color = Skerry.colors.ink)
            }
        }
    }
}

/** Search field over host name/address/username/group. */
@Composable
private fun HostsSearch(query: String, onChange: (String) -> Unit) {
    // Outer padding is on the wrapper; the border lives in decorationBox so a click anywhere places the caret.
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Skerry.colors.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.card)
                    .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(11.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("search", size = 19.sp, color = Skerry.colors.faint)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Txt(stringResource(Res.string.shell_search_hosts), color = Skerry.colors.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/** Filter-chip row: "All" + tags (prefixed with `#`); active chip highlighted cyan, horizontally scrollable. */
@Composable
private fun HostsChips(chips: List<String>, active: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        chips.forEach { chip ->
            key(chip) {
                val on = chip == active
                val onClick = remember(chip) { { onSelect(chip) } }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) Skerry.colors.cyan14 else Skerry.colors.overlayMed)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                        .padding(horizontal = 13.dp, vertical = 5.dp),
                ) {
                    Txt(
                        hostChipLabel(chip),
                        color = if (on) Skerry.colors.cyanBright else Skerry.colors.dim,
                        size = 12.5.sp,
                        weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Folder section header: collapse chevron + uppercase name + (edit pencil) + host count. Chevron
 * click toggles collapsed state, pencil click opens the Rename/Delete group dialog — hit zones are
 * strictly on the icons ([onToggle]/[onEdit]) so taps don't conflict with header drag (folder
 * reorder), as on desktop. [dropTarget] highlights the uppercase name when a host is dropped here.
 * [onEdit] == null for the synthetic "Ungrouped" bucket and the preview path (pencil hidden).
 */
@Composable
private fun MobileFolderHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    dropTarget: Boolean,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        }
        Txt(
            name.uppercase(),
            color = if (dropTarget) Skerry.colors.cyanBright else Skerry.colors.faint,
            size = 12.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        if (onEdit != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEdit,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Sym("edit", size = 14.sp, color = Skerry.colors.faint)
            }
        }
        Spacer(Modifier.weight(1f))
        Txt(count.toString(), color = Skerry.colors.faint, size = 11.sp)
    }
}

/**
 * Host row: icon tile + name + monospace `user@address` + status dot. The tile carries the
 * profile's protocol ([app.skerry.ui.host.icon], same symbol as the desktop sidebar and the
 * connection form); the dot color is live, taken from the host's latest session status
 * ([SessionsController.statusFor]) via the desktop-shared [sessionDotColor] (connected → green,
 * connecting → amber, error/dropped → sunset, no session → dim). Reading uiState inside the
 * composition subscribes the row to status changes so the dot updates on connect.
 */
@Composable
private fun MobileHostRow(host: Host, onClick: () -> Unit) {
    val dotColor = sessionDotColor(LocalSessions.current?.statusFor(host.id))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Skerry.colors.cyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym(host.connectionType.icon, size = 21.sp, color = Skerry.colors.cyanBright)
        }
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt(
                "${host.username}@${host.address}",
                color = Skerry.colors.dim,
                size = 11.5.sp,
                font = LocalFonts.current.mono,
                maxLines = 1,
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
    }
}

