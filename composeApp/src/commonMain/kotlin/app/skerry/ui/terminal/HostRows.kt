package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.host.rowLabel
import app.skerry.ui.host.rowSubtitle
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalConnectHost
import app.skerry.shared.ssh.hasShell
import app.skerry.ui.app.LocalRunSnippetOnHost
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.design.DragFolder
import app.skerry.ui.design.Badge
import app.skerry.ui.design.Dot
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.MenuItem
import app.skerry.ui.design.MenuPanel
import app.skerry.ui.design.RowNoteTooltip
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.rememberRowNote
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_menu_delete
import app.skerry.ui.generated.resources.term_menu_duplicate
import app.skerry.ui.generated.resources.term_menu_edit
import app.skerry.ui.generated.resources.term_menu_run_snippet
import app.skerry.ui.design.FolderDragState
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.MockHost
import app.skerry.ui.host.ProdBadge
import app.skerry.ui.host.isProdHost
import app.skerry.ui.host.color
import app.skerry.ui.design.draggableItemRow
import app.skerry.ui.design.itemBoundsAnchor
import app.skerry.ui.design.visibleItemIds
import app.skerry.ui.host.icon
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.sessionDotColor
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry
import app.skerry.ui.generated.resources.shell_tip_more_actions

/** Shared team host row, like [RecentHostRow]; the team-vault origin is marked by the section header. */
@Composable
internal fun TeamHostRow(host: Host, mono: FontFamily) {
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
            // Filtered once per profile, not once per repaint of a list that draws every row it has.
            val name = remember(host) { host.rowLabel() }
            val subtitle = remember(host) { host.rowSubtitle() }
            Txt(name, color = Skerry.colors.dim, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt(subtitle, color = Skerry.colors.faint, size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Recent-connection row: protocol icon + host name ([Host.label]) with a `user@address` secondary
 * caption below it. History is already stated by the section header, so the icon marks the protocol
 * instead. Click reconnects via [LocalConnectHost], same path as clicking a catalog row.
 */
@Composable
internal fun RecentHostRow(host: Host, mono: FontFamily) {
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
            val name = remember(host) { host.rowLabel() }
            val subtitle = remember(host) { host.rowSubtitle() }
            Txt(
                name,
                color = Skerry.colors.dim, size = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Txt(
                subtitle,
                color = Skerry.colors.faint, size = 10.5.sp, font = mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Small caption for a connection-type sub-group inside the no-group bucket. */
@Composable
internal fun HostTypeSubheader(label: String) {
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
internal fun HostRow(
    host: Host,
    state: DesktopDesignState,
    controller: HostManagerController,
    sessions: SessionsController?,
    connect: (Host) -> Unit,
    mono: FontFamily,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    dragState: FolderDragState,
    foldersProvider: () -> List<DragFolder>,
) {
    // Stabilizes lambdas on (host, ...): otherwise every folder recomposition would recreate them
    // and force the row to redraw (nullable functions are unstable).
    val onClick = remember(host, connect) { { connect(host) } }
    val onSelect = remember(host) { { onSelectHost(host.id) } }
    val onEdit = remember(host, state) { { state.openEditModal(host) } }
    val onDuplicate = remember(host, state) { { state.openDuplicateModal(host) } }
    val onDelete = remember(host, state) { { state.requestDeleteHost(host) } }
    // Forgets the row's geometry once the host leaves the list (deleted/filtered out).
    DisposableEffect(host.id) { onDispose { dragState.clearItemBounds(host.id) } }
    Box(
        Modifier
            .alpha(if (dragState.draggingItemId == host.id) 0.4f else 1f)
            .itemBoundsAnchor(dragState, host.id)
            .draggableItemRow(dragState, host.id, foldersProvider) { drop ->
                // Index is relative to the rows this sidebar shows, so the controller is told which
                // ones those were (section, search and tag chip all narrow them) and translates it.
                controller.moveHostInSection(host.id, drop.group, drop.index, foldersProvider().visibleItemIds())
            },
    ) {
        HostEntryRow(
            label = remember(host) { host.rowLabel() },
            // Selection highlight: marks the row clicked in double-click mode (and the most recently
            // connected one in single-click mode). Distinct from the live-connection status dot.
            selected = host.id == selectedHostId,
            dot = sessionDotColor(sessions?.sessionStatusFor(host.id) ?: SessionStatus.Idle),
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
internal fun DropLine() {
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
internal fun HostRow(host: MockHost, state: DesktopDesignState, mono: FontFamily) {
    HostEntryRow(
        // Design data, not a peer's — but [HostEntryRow] draws its label as given, so it is filtered
        // here like every other caller's.
        label = untrustedLabel(host.name),
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
 * ([host] != null, [LocalSnippets] is present, and the host is not a remote desktop — a framebuffer
 * has no shell to run one in), a trailing "⋮" button opens a menu (Run
 * snippet.../Edit/Duplicate/Delete); its click is intercepted before [onClick], so opening the menu
 * doesn't trigger a connection. [label] is drawn as given: a caller holding a profile passes
 * [app.skerry.ui.host.rowLabel], never [app.skerry.shared.host.Host.label] itself. "Run snippet..." opens the snippet picker and runs it on [host] via
 * [LocalRunSnippetOnHost]. A profile carrying [Host.notes] shows them as a hover tooltip.
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
    // Production marker: shown before the row is ever clicked, so "wrong window" is visible in the
    // list itself and not only once a session is open.
    // Remembered like the row's other filtered values: the tag walk is per tag, and this row
    // recomposes on hover.
    val prod = remember(host) { isProdHost(host) }
    val snippets = LocalSnippets.current
    val runSnippetOnHost = LocalRunSnippetOnHost.current
    // Not on a remote desktop: a snippet is a line typed into a shell, and a framebuffer has none —
    // the row would open the desktop and the command would have nowhere to go.
    val canRunSnippet = host != null && snippets != null && host.connectionType.hasShell
    val hasMenu = onEdit != null || onDuplicate != null || onDelete != null || canRunSnippet
    var menuOpen by remember { mutableStateOf(false) }
    var snippetPickerOpen by remember { mutableStateOf(false) }
    // The profile's note is shown on hover — mouse-only by nature; on Android the same code simply
    // never reports a hover (the note lives in the host detail screen there).
    val note = rememberRowNote(host?.notes)
    // The tooltip is a sibling of the row, not a child: inside the row its (zero-sized) popup node
    // would still collect the row's 8dp item spacing and shift the label sideways on hover.
    Box(Modifier.fillMaxWidth()) {
        // Suppressed while the row's own popups are up, so the note doesn't land on top of them.
        RowNoteTooltip(note, suppressed = menuOpen || snippetPickerOpen)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
                .hoverable(note.interaction)
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
                // Already sanitized by the caller ([app.skerry.ui.host.rowLabel]); filtering again
                // here would only repeat the scan on the sidebar's longest list.
                label,
                color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim, size = 11.5.sp, font = mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (prod) ProdBadge()
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
                        label = stringResource(Res.string.shell_tip_more_actions),
                        onClick = { menuOpen = !menuOpen },
                        modifier = Modifier.focusProperties { canFocus = false },
                        box = 22, icon = 16.sp, tint = Skerry.colors.faint,
                    )
                    if (menuOpen) {
                        Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                            MenuPanel {
                                if (canRunSnippet) {
                                    MenuItem(stringResource(Res.string.term_menu_run_snippet)) { menuOpen = false; snippetPickerOpen = true }
                                }
                                onEdit?.let { edit ->
                                    MenuItem(stringResource(Res.string.term_menu_edit)) { menuOpen = false; edit() }
                                }
                                onDuplicate?.let { duplicate ->
                                    MenuItem(stringResource(Res.string.term_menu_duplicate)) { menuOpen = false; duplicate() }
                                }
                                onDelete?.let { delete ->
                                    MenuItem(stringResource(Res.string.term_menu_delete), Skerry.colors.sunset) { menuOpen = false; delete() }
                                }
                            }
                        }
                    }
                    // Snippet picker: runs on this host (opens/reuses a session and runs the command after
                    // connecting). An empty library shows "No snippets yet".
                    // Gated on the same condition as the item that opens it: a profile switched to
                    // a remote desktop while the picker is up (a team change landing over sync)
                    // would otherwise still run the line it has nowhere to send. [canRunSnippet]
                    // already carries the two null checks the picker needs.
                    if (snippetPickerOpen && canRunSnippet && host != null) {
                        Popup(
                            alignment = Alignment.TopEnd,
                            onDismissRequest = { snippetPickerOpen = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            SnippetPalette(snippets) { entry ->
                                // Through the manager: a snippet with ${{…}} variables opens the confirm
                                // dialog first; the resolved line (newline included) lands here after.
                                snippets.run(entry.id, oneTap = true) { line, secrets -> runSnippetOnHost(host, line, secrets) }
                                snippetPickerOpen = false
                            }
                        }
                    }
                }
            }
        }
    }
}

