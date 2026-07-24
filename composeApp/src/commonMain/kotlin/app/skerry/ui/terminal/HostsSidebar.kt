package app.skerry.ui.terminal

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
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
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.app.LocalHostClickConnectMode
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
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.host.pickAndParseSshConfig
import app.skerry.ui.generated.resources.conn_import_action
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SidebarSearchField
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_hosts_section
import app.skerry.ui.generated.resources.term_menu_delete
import app.skerry.ui.generated.resources.term_menu_duplicate
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
import app.skerry.ui.host.color
import app.skerry.ui.host.draggableFolderHeader
import app.skerry.ui.host.draggableHostRow
import app.skerry.ui.host.filterHosts
import app.skerry.ui.host.folderHeaderAnchor
import app.skerry.ui.host.folderRangeAnchor
import app.skerry.ui.host.connectionTypeLabel
import app.skerry.ui.host.groupHostsByConnectionType
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.host.hostBoundsAnchor
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.host.hostTagChips
import app.skerry.ui.host.icon
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.sessionDotColor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Terminal view host sidebar: search, tag filters, catalog folders (live drag-and-drop or mock
// preview), a RECENT section, and the "New connection" button.

/**
 * Double-click window for host rows, between two PRESS events (like [app.skerry.ui.sftp]'s file
 * rows). Compose's own onDoubleClick rides the desktop ViewConfiguration's 300ms up→down window —
 * tighter than OS double-click conventions (GNOME 400ms, Windows 500ms press-to-press) — so
 * relaxed double clicks missed it and the row connected "every other time".
 */
private const val HOST_DOUBLE_CLICK_MS = 400L

/** [HOST_DOUBLE_CLICK_MS] press-tracking reset: far enough in the past that no press pairs with it. */
private const val NO_PRESS = Long.MIN_VALUE / 2

/**
 * Host-row connect click behavior from Settings → Terminal → Behavior: single click connects
 * directly, double click requires a second click. Desktop-only (mobile always connects on tap).
 *
 * Double-click mode keeps two affordances:
 * - A single mouse click still *does* something: [onSingleClick] runs when provided (live catalog
 *   rows use it for selection highlight); otherwise clickable's press feedback shows the
 *   row isn't inert.
 * - Keyboard activation (Enter/Space) connects directly — the double-click requirement applies to
 *   the mouse only, so keyboard-only users can always connect.
 */
@Composable
private fun Modifier.hostConnectClick(
    onClick: () -> Unit,
    onSingleClick: (() -> Unit)? = null,
): Modifier =
    when (LocalHostClickConnectMode.current) {
        HostClickConnectMode.SingleClick -> clickable(onClick = onClick)
        HostClickConnectMode.DoubleClick -> {
            // Selection fires on the press *Release*, not on clickable's onClick, keeping the
            // highlight immediate while a press that turns into a drag-reorder or a drag-scroll
            // emits Cancel (not Release) and doesn't spuriously select the row under the pointer.
            val interaction = remember { MutableInteractionSource() }
            val select = rememberUpdatedState(onSingleClick)
            LaunchedEffect(interaction) {
                interaction.interactions.collect { if (it is PressInteraction.Release) select.value?.invoke() }
            }
            val connect = rememberUpdatedState(onClick)
            // Chain onto `this` (not a fresh Modifier): the receiver already carries the row's
            // fillMaxWidth/padding/clip, and starting over would drop them — the row would lose its
            // left indent and stop filling the width, so it shifts when the mode changes.
            // onPreviewKeyEvent must sit *outer* to clickable: key events travel root→focused on
            // the preview pass, and clickable consumes Enter/Space itself, so a descendant
            // onKeyEvent never fires. The preview handler intercepts first, making Enter/Space
            // connect while the mouse still requires a double click (same pattern as
            // TerminalScreen/CommandPalette).
            this
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.Spacebar)
                    ) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = {},
                )
                // The double click itself: raw PRESS counting in one loop, like SftpView's
                // LiveFileRow — immune to Compose's 300ms up→down window (see HOST_DOUBLE_CLICK_MS)
                // and to other gestures consuming the tap. Time comes from the event itself
                // (uptimeMillis) — deterministic. Sits after clickable in the chain so it observes
                // the Main pass before it; a press that arrives already consumed belongs to a
                // descendant (the row's "⋮" menu button) and must not count toward a row
                // double-click. Only primary-button presses count: middle/right have no row action
                // (and middle is its own gesture on session tabs), so a left+middle chord or two
                // middle clicks must not connect.
                .pointerInput(Unit) {
                    var lastDownMs = NO_PRESS
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Press || !e.buttons.isPrimaryPressed ||
                                e.buttons.isSecondaryPressed || e.buttons.isTertiaryPressed
                            ) continue
                            val change = e.changes.first()
                            if (change.isConsumed) continue
                            val t = change.uptimeMillis
                            if (t - lastDownMs <= HOST_DOUBLE_CLICK_MS) {
                                connect.value()
                                lastDownMs = NO_PRESS // a triple click doesn't connect twice
                            } else {
                                lastDownMs = t
                            }
                        }
                    }
                }
        }
    }

@Composable
internal fun HostsSidebar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val liveHosts = LocalHosts.current
    // Manual reorder (drag-and-drop) state for the live catalog; unused on the mock path.
    val dragState = remember { HostDragState() }
    // Selected host in the live catalog — drives the single-click highlight in double-click
    // connect mode (file-manager convention: click selects, double-click opens). Also updated on
    // connect so the row that just opened reads as selected. Null = no selection.
    var selectedHostId by remember { mutableStateOf<String?>(null) }
    // Active filter chip (tag); live catalog only, chips are static on the mock path.
    var activeChip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    val chips = liveHosts?.let { remember(it.hosts) { hostTagChips(it.hosts) } } ?: emptyList()
    // If the active tag disappears (host edited/deleted), the filter falls back to "All".
    val effectiveChip = if (activeChip in chips) activeChip else ALL_HOSTS_CHIP
    Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            // Search and the collapse control share the header. The chevron pulls the search field
            // in from the right edge (it no longer runs to the panel border) and collapses the
            // sidebar; the reopen handle then lives at the terminal's left edge (SidebarReopenHandle).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HostSearchField(state, Modifier.weight(1f))
                IconBtn("chevron_left", onClick = state::toggleSidebar, box = 30, icon = 18.sp, tint = Skerry.colors.faint)
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
                SidebarSectionTitle(stringResource(Res.string.term_hosts_section))
                // Create a new (initially empty) group in the live catalog; decorative on the mock path.
                if (liveHosts != null) {
                    IconBtn("create_new_folder", onClick = state::openCreateGroup, box = 20, icon = 14.sp, tint = Skerry.colors.faint)
                } else {
                    Sym("create_new_folder", size = 14.sp, color = Skerry.colors.faint)
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
                        color = Skerry.colors.faint, size = 12.sp,
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
                        LiveHostFolder(folder, state, mono, dragState, liveHosts, selectedHostId, { selectedHostId = it }) { foldersUpdated.value }
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
                    // Divider belongs to the section: hidden together with it when RECENT is off/empty.
                    HLine(modifier = Modifier.padding(top = 8.dp))
                    RecentSectionHeader()
                    recent.forEach { host -> key(host.id) { RecentHostRow(host, mono) } }
                }
            } else {
                HLine(modifier = Modifier.padding(top = 8.dp))
                RecentSectionHeader()
                Row(
                    Modifier.padding(start = 16.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("history", size = 14.sp, color = Skerry.colors.faint)
                    Txt("user@vps.example.com", color = Skerry.colors.dim, size = 11.5.sp, font = mono)
                }
            }
        }
        HLine()
        // Fixed to the AI bar's idle height so both bottom strips share one top line.
        val importScope = rememberCoroutineScope()
        Box(Modifier.height(BOTTOM_BAR_HEIGHT).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(stringResource(Res.string.term_new_connection), onClick = state::openModal, icon = "add_link", modifier = Modifier.weight(1f))
                // Import hosts from an OpenSSH ~/.ssh/config: pick + parse off the main thread, then the
                // preview modal (rendered at the app root) handles selection and persistence. Shown only
                // on the live path — importing needs a host store to write to (mock/preview has none).
                if (liveHosts != null) {
                    IconBtn(
                        "download",
                        onClick = { importScope.launch { pickAndParseSshConfig()?.let(state::beginSshImport) } },
                        tooltip = stringResource(Res.string.conn_import_action),
                    )
                }
            }
        }
    }
}

/**
 * Search field for the host sidebar (name/address/user/group/tags). Border/icon/placeholder live
 * in decorationBox so a click anywhere places the caret. Shows a `⌘K` badge when empty, a clear
 * cross once text is entered.
 */
@Composable
private fun HostSearchField(state: DesktopDesignState, modifier: Modifier = Modifier) {
    SidebarSearchField(state.hostSearchQuery, state::onHostSearch, stringResource(Res.string.term_search_hosts_placeholder), modifier)
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
private fun RecentSectionHeader() {
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

/** Shared team host row, like [RecentHostRow]; the team-vault origin is marked by the section header. */
@Composable
private fun TeamHostRow(host: Host, mono: FontFamily) {
    val connect = LocalConnectHost.current
    val onClick = remember(host, connect) { { connect(host) } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(5.dp))
            .hostConnectClick(onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(host.connectionType.icon, size = 14.sp, color = Skerry.colors.faint)
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = Skerry.colors.dim, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt("${host.username}@${host.address}", color = Skerry.colors.faint, size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Recent-connection row: protocol icon + host name ([Host.label]) with a `user@address` secondary
 * caption below it. History is already stated by the section header, so the icon marks the protocol
 * instead. Click reconnects via [LocalConnectHost], same path as clicking a catalog row.
 */
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
            .hostConnectClick(onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(host.connectionType.icon, size = 14.sp, color = Skerry.colors.faint)
        Column(Modifier.weight(1f)) {
            Txt(
                host.label,
                color = Skerry.colors.dim, size = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Txt(
                "${host.username}@${host.address}",
                color = Skerry.colors.faint, size = 10.5.sp, font = mono,
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
                .draggableFolderHeader(dragState, folder.name, foldersProvider) { index ->
                    controller.moveFolder(group, index)
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
                            HostRow(host, state, controller, sessions, connect, mono, selectedHostId, onSelectHost, dragState, foldersProvider)
                        }
                    }
                }
            } else {
                // key(host.id): row positional identity is pinned to the host, so an open menu/row
                // state doesn't jump to a neighbor when the catalog reorders after an edit.
                folder.hosts.forEach { host ->
                    key(host.id) {
                        if (host.id == lineBeforeId) DropLine()
                        HostRow(host, state, controller, sessions, connect, mono, selectedHostId, onSelectHost, dragState, foldersProvider)
                    }
                }
                // Drop at the folder's end: the line goes after the last row.
                if (dropIndex != null && dropIndex == others.size) DropLine()
            }
        }
    }
}

/** Small caption for a connection-type sub-group inside the no-group bucket. */
@Composable
private fun HostTypeSubheader(label: String) {
    Txt(
        label,
        color = Skerry.colors.faint,
        size = 9.5.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 3.dp),
    )
}

/** One host row: connection click, selection highlight, live-status dot, drag handle, and context menu. */
@Composable
private fun HostRow(
    host: Host,
    state: DesktopDesignState,
    controller: HostManagerController,
    sessions: SessionsController?,
    connect: (Host) -> Unit,
    mono: FontFamily,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    dragState: HostDragState,
    foldersProvider: () -> List<HostFolder>,
) {
    // Stabilizes lambdas on (host, ...): otherwise every folder recomposition would recreate them
    // and force the row to redraw (nullable functions are unstable).
    val onClick = remember(host, connect) { { connect(host) } }
    val onSelect = remember(host) { { onSelectHost(host.id) } }
    val onEdit = remember(host, state) { { state.openEditModal(host) } }
    val onDuplicate = remember(host, state) { { state.openDuplicateModal(host) } }
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
            // Selection highlight: marks the row clicked in double-click mode (and the most recently
            // connected one in single-click mode). Distinct from the live-connection status dot.
            selected = host.id == selectedHostId,
            dot = sessionDotColor(sessions?.statusFor(host.id)),
            badge = null,
            onClick = onClick,
            onSelect = onSelect,
            mono = mono,
            icon = host.connectionType.icon,
            // Host object, for the "Run snippet..." menu item (runs a snippet on this host).
            host = host,
            // Edit/duplicate/delete the profile via the context menu (right-click/long-press).
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
        )
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
            .background(Skerry.colors.cyan),
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
        icon = host.connectionType.icon,
    )
}

/**
 * Shared host row for the sidebar (mock and live catalog): status dot + protocol icon + name +
 * optional badge. [icon] is the profile's [app.skerry.ui.host.icon] and stays [Skerry.colors.faint] — the two
 * markers read as separate axes, colour for session status and shape for protocol. Clicking the row
 * connects ([onClick]). When
 * [onEdit]/[onDuplicate]/[onDelete] are provided (live catalog) or a snippet can be run on the host
 * ([host] != null and [LocalSnippets] is present), a trailing "⋮" button opens a menu (Run
 * snippet.../Edit/Duplicate/Delete); its click is intercepted before [onClick], so opening the menu
 * doesn't trigger a connection. "Run snippet..." opens the snippet picker and runs it on [host] via
 * [LocalRunSnippetOnHost].
 */
@Composable
internal fun HostEntryRow(
    label: String,
    selected: Boolean,
    dot: Color,
    badge: String?,
    onClick: () -> Unit,
    mono: FontFamily,
    icon: String,
    host: Host? = null,
    onSelect: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val snippets = LocalSnippets.current
    val runSnippetOnHost = LocalRunSnippetOnHost.current
    val canRunSnippet = host != null && snippets != null
    val hasMenu = onEdit != null || onDuplicate != null || onDelete != null || canRunSnippet
    var menuOpen by remember { mutableStateOf(false) }
    var snippetPickerOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .hostConnectClick(
                onClick = {
                    // Connecting also marks the row selected (single-click mode too — it reads as
                    // "the host you just opened"), then opens the session.
                    onSelect?.invoke()
                    onClick()
                },
                onSingleClick = onSelect,
            )
            .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Sym(icon, size = 13.sp, color = Skerry.colors.faint)
        Txt(
            label,
            color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim, size = 11.5.sp, font = mono,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            val strict = badge == "STRICT"
            Badge(badge, bg = if (strict) Skerry.colors.strictBg else Skerry.colors.devBg, fg = if (strict) Skerry.colors.strictFg else Skerry.colors.moss)
        }
        if (hasMenu) {
            Box {
                // Mouse-only: keeping the menu out of Tab traversal makes one Tab = one host row
                // (otherwise every row costs two presses); keyboard users get Enter/Space to connect.
                IconBtn(
                    "more_vert",
                    onClick = { menuOpen = !menuOpen },
                    modifier = Modifier.focusProperties { canFocus = false },
                    box = 22, icon = 16.sp, tint = Skerry.colors.faint,
                )
                if (menuOpen) {
                    Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                        Column(
                            Modifier.clip(RoundedCornerShape(7.dp)).background(Skerry.colors.surface2).border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(7.dp)).padding(4.dp),
                        ) {
                            if (canRunSnippet) {
                                HostMenuItem(stringResource(Res.string.term_menu_run_snippet), Skerry.colors.text) { menuOpen = false; snippetPickerOpen = true }
                            }
                            onEdit?.let { edit ->
                                HostMenuItem(stringResource(Res.string.term_menu_edit), Skerry.colors.text) { menuOpen = false; edit() }
                            }
                            onDuplicate?.let { duplicate ->
                                HostMenuItem(stringResource(Res.string.term_menu_duplicate), Skerry.colors.text) { menuOpen = false; duplicate() }
                            }
                            onDelete?.let { delete ->
                                HostMenuItem(stringResource(Res.string.term_menu_delete), Skerry.colors.sunset) { menuOpen = false; delete() }
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
                            // Through the manager: a snippet with ${{…}} variables opens the confirm
                            // dialog first; the resolved line (newline included) lands here after.
                            snippets.run(entry.id) { line -> runSnippetOnHost(host, line) }
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
