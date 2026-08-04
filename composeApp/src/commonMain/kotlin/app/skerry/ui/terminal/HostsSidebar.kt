package app.skerry.ui.terminal

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.app.LocalHostClickConnectMode
import app.skerry.ui.design.Chip
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.host.pickAndParseRdpFile
import app.skerry.ui.host.pickAndParseSshConfig
import app.skerry.ui.generated.resources.conn_import_action
import app.skerry.ui.generated.resources.conn_rdp_import_action
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SidebarSearchField
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_search_placeholder
import app.skerry.ui.generated.resources.rd_section
import app.skerry.ui.generated.resources.term_hosts_section
import app.skerry.ui.generated.resources.term_new_connection
import app.skerry.ui.generated.resources.term_no_hosts_match
import app.skerry.ui.generated.resources.term_search_hosts_placeholder
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.HOST_GROUPS
import app.skerry.ui.host.HostDragState
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection
import app.skerry.ui.host.color
import app.skerry.ui.host.filterHosts
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.host.sidebarFolders
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.host.hostTagChips
import app.skerry.ui.host.icon
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
 * Height of the sidebar's bottom strip (the "New connection" button): a fixed height keeps the
 * button box the same size whatever the button label's language does to its width.
 */
private val SIDEBAR_FOOTER_HEIGHT = 48.dp

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
internal fun Modifier.hostConnectClick(
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

/**
 * Host sidebar of a work-area section. [section] narrows the catalog to the profiles that belong
 * here (terminal-style connections or remote desktops) — everything else (search, tag chips,
 * folders, drag-and-drop, RECENT, team hosts) then works within that slice, so the two sections
 * read as separate lists over one store.
 */
@Composable
internal fun HostsSidebar(state: DesktopDesignState, section: HostSection = HostSection.Terminal) {
    val mono = LocalFonts.current.mono
    val liveHosts = LocalHosts.current
    // Manual reorder (drag-and-drop) state for the live catalog; unused on the mock path.
    val dragState = remember { HostDragState() }
    // Selected host in the live catalog — drives the single-click highlight in double-click
    // connect mode (file-manager convention: click selects, double-click opens). Also updated on
    // connect so the row that just opened reads as selected. Null = no selection.
    var selectedHostId by remember { mutableStateOf<String?>(null) }
    // This section's slice of the catalog: everything below (chips, folders, RECENT, drag targets)
    // is derived from it, so a remote desktop never shows up among the shells and vice versa.
    val sectionHosts = liveHosts?.let { remember(it.hosts, section) { it.hosts.inSection(section) } } ?: emptyList()
    // Active filter chip (tag); live catalog only, chips are static on the mock path.
    var activeChip by remember { mutableStateOf(ALL_HOSTS_CHIP) }
    // Tags of THIS section only: a chip that would filter the list down to nothing is noise.
    val chips = liveHosts?.let { remember(sectionHosts) { hostTagChips(sectionHosts) } } ?: emptyList()
    // If the active tag disappears (host edited/deleted), the filter falls back to "All".
    val effectiveChip = if (activeChip in chips) activeChip else ALL_HOSTS_CHIP
    Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            // Search alone in the header: collapsing is the work bar's chevron (and the rail's
            // toggle), so a third control here only took room from the search field.
            HostSearchField(state, section, Modifier.fillMaxWidth())
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
                SidebarSectionTitle(
                    stringResource(
                        when (section) {
                            HostSection.Terminal -> Res.string.term_hosts_section
                            HostSection.RemoteDesktops -> Res.string.rd_section
                        },
                    ),
                )
                // Create a new (initially empty) group in the live catalog; decorative on the mock path.
                // The folder is remembered for this section's sidebar (see [CustomGroup]).
                if (liveHosts != null) {
                    IconBtn("create_new_folder", onClick = { state.openCreateGroup(section) }, box = 20, icon = 14.sp, tint = Skerry.colors.faint)
                } else {
                    Sym("create_new_folder", size = 14.sp, color = Skerry.colors.faint)
                }
            }
            // Live catalog from HostManagerController when provided (behind the vault gate), otherwise
            // mock data (offscreen render/preview path). Folders are grouped and narrowed by the active tag.
            if (liveHosts != null) {
                val query = state.hostSearchQuery
                val folders = remember(sectionHosts, liveHosts.hosts, effectiveChip, query, state.customGroups, section) {
                    val filtered = filterHosts(sectionHosts, effectiveChip, query)
                    // Empty user groups are shown as folders with no hosts, but only outside a filter
                    // (search/tag narrow by host, and an empty folder has nothing to match) and only
                    // in the sidebar they were created in — [sidebarFolders] also drops the ones that
                    // meanwhile got hosts, wherever those hosts landed.
                    if (query.isNotBlank() || effectiveChip != ALL_HOSTS_CHIP) {
                        groupHostsByFolder(filtered)
                    } else {
                        sidebarFolders(filtered, liveHosts.hosts, state.customGroupsIn(section))
                    }
                }
                // An empty remote-desktop catalog says so, instead of leaving a blank column above
                // the "New connection" button (the terminal section always has the local shell path).
                if (folders.isEmpty() && section == HostSection.RemoteDesktops &&
                    query.isBlank() && effectiveChip == ALL_HOSTS_CHIP
                ) {
                    EmptyCatalogNote()
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
                        LiveHostFolder(folder, state, section, mono, dragState, liveHosts, selectedHostId, { selectedHostId = it }) { foldersUpdated.value }
                    }
                }
                if (folderLineIndex != null && folderLineIndex == otherFolders.size) DropLine()
                // Shared team hosts: per-team sections below the personal catalog, shown only outside
                // search/filter since those narrow the personal catalog.
                if (query.isBlank() && effectiveChip == ALL_HOSTS_CHIP) {
                    TeamHostsSection(liveHosts.hosts, state, section, mono)
                }
            } else {
                HOST_GROUPS.forEach { group -> HostGroupBlock(group, state, section, mono) }
            }
            // Live catalog: RECENT section from actual connection history ([DesktopDesignState.recentHostIds]),
            // resolved against current profiles; deleted/unknown ids are simply hidden, empty means no section.
            // Mock/preview (no live catalog): a static row.
            if (liveHosts != null) {
                // The section can be hidden entirely (Settings -> Appearance -> Interface) and size-limited.
                // Memoized by (recent order, catalog contents, limit), like the `folders` above, so the
                // resolve doesn't rerun on every sidebar recomposition (drag/chip switch/tab switch).
                // Narrowed to this section: history is per catalog too, so the desktops list doesn't
                // offer to reconnect a shell. The limit applies after narrowing, so a section with
                // few recents still fills its quota.
                val recent = remember(state.recentHostIds, liveHosts.hosts, state.settings.recentLimit, section) {
                    state.recentHostIds.mapNotNull { liveHosts.find(it) }
                        .inSection(section)
                        .take(state.settings.recentLimit)
                }
                if (state.settings.showRecent && recent.isNotEmpty()) {
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
        val importScope = rememberCoroutineScope()
        Box(Modifier.height(SIDEBAR_FOOTER_HEIGHT).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The form opens on this section's protocols: a remote desktop is never created from
                // the terminal list, and a shell never from the desktops list.
                PrimaryButton(
                    stringResource(Res.string.term_new_connection),
                    onClick = { state.openModal(section) },
                    icon = "add_link",
                    modifier = Modifier.weight(1f),
                )
                // Import hosts from an OpenSSH ~/.ssh/config: pick + parse off the main thread, then the
                // preview modal (rendered at the app root) handles selection and persistence. Shown only
                // on the live path — importing needs a host store to write to (mock/preview has none)
                // and only under the terminal section: an ssh_config holds SSH hosts, not desktops.
                if (liveHosts != null && section == HostSection.Terminal) {
                    IconBtn(
                        "download",
                        onClick = { importScope.launch { pickAndParseSshConfig()?.let(state::beginSshImport) } },
                        tooltip = stringResource(Res.string.conn_import_action),
                    )
                }
                // The desktops section imports the format its own clients write: one `.rdp` file is
                // one connection, so this opens a confirm modal rather than a selection list.
                if (liveHosts != null && section == HostSection.RemoteDesktops) {
                    IconBtn(
                        "download",
                        onClick = { importScope.launch { pickAndParseRdpFile()?.let(state::beginRdpImport) } },
                        tooltip = stringResource(Res.string.conn_rdp_import_action),
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
private fun HostSearchField(state: DesktopDesignState, section: HostSection, modifier: Modifier = Modifier) {
    val placeholder = when (section) {
        HostSection.Terminal -> Res.string.term_search_hosts_placeholder
        HostSection.RemoteDesktops -> Res.string.rd_search_placeholder
    }
    SidebarSearchField(state.hostSearchQuery, state::onHostSearch, stringResource(placeholder), modifier)
}
