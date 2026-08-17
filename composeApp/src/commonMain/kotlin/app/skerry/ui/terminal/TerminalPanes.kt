package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.desktop.matchDesktopShortcut
import app.skerry.ui.desktop.paneGridDirection
import app.skerry.shared.host.Host
import app.skerry.ui.app.LocalConnectPane
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.rememberModalPresence
import app.skerry.ui.design.ModalPresence
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.MenuActionRow
import app.skerry.ui.design.MenuPanel
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_no_hosts_in_catalog
import app.skerry.ui.generated.resources.term_pane_change_host
import app.skerry.ui.generated.resources.term_pane_close
import app.skerry.ui.generated.resources.term_pane_menu
import app.skerry.ui.generated.resources.term_select_host_placeholder
import app.skerry.ui.host.rowLabel
import app.skerry.ui.session.PaneDragState
import app.skerry.ui.session.PaneEdge
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.Tab
import app.skerry.ui.session.draggablePaneHeader
import app.skerry.ui.session.paneBoundsAnchor
import app.skerry.ui.session.paneResizeCursor
import app.skerry.ui.session.sessionDotColor
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection

/** Grabbable width of a divider between panes; the line drawn inside it stays a hairline. */
private val PANE_DIVIDER_GRIP = 6.dp

/**
 * The active tab's panes on its grid ([Session.layout]): rows top to bottom, panes left to
 * right inside a row, dividers in between. Each pane is an independent session with its own header
 * and terminal; the focused one carries the accent strip, and headers drag panes to another slot.
 */
@Composable
internal fun ColumnScope.PaneGrid(
    sessions: SessionsController,
    tab: Tab,
    state: DesktopDesignState,
) {
    // Per tab: one tab's pane geometry must not resolve drops on another's grid.
    val drag = remember(tab.id) { PaneDragState() }
    // Divider drags arrive in pixels but the layout is in shares of the grid, so its size is the
    // conversion factor — measured here, since rows are full-width and the column is full-height.
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = tab.layout
    // Keyboard navigation between panes. Preview events reach here on their way down to the focused
    // terminal, so the chord is claimed only while the keyboard is inside the grid — in a text field,
    // on the file panel or on a remote desktop the same keys keep their usual meaning. Claimed even
    // when there is no pane that way (unsplit tab, edge of the grid): letting it reach the terminal
    // would send ESC[1;6D, which a shell that doesn't know the sequence echoes as a stray "D".
    val onGridKey: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val shortcut = matchDesktopShortcut(
                event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
            )
            val direction = paneGridDirection(shortcut, searchOpen = tab.focusedPane.liveTerminal?.search?.query != null)
            if (direction == null) false else { sessions.focusNeighborPane(direction); true }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent(onGridKey).onGloballyPositioned { gridSize = it.size },
    ) {
        layout.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                PaneDivider(vertical = false) { px ->
                    if (gridSize.height > 0) sessions.resizePaneRows(tab.id, rowIndex - 1, px / gridSize.height)
                }
            }
            Row(Modifier.weight(row.weight).fillMaxWidth()) {
                row.cells.forEachIndexed { columnIndex, cell ->
                    if (columnIndex > 0) {
                        PaneDivider(vertical = true) { px ->
                            if (gridSize.width > 0) sessions.resizePaneCells(tab.id, rowIndex, columnIndex - 1, px / gridSize.width)
                        }
                    }
                    val pane = tab.pane(cell.paneId)
                    if (pane != null) {
                        key(pane.id) {
                            PaneCell(
                                sessions, tab, pane, state, drag,
                                row = rowIndex,
                                column = columnIndex,
                                modifier = Modifier.weight(cell.weight).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One pane of the grid: its header, its terminal, and the drop indicator while a pane is dragged. */
@Composable
internal fun PaneCell(
    sessions: SessionsController,
    tab: Tab,
    pane: Session,
    state: DesktopDesignState,
    drag: PaneDragState,
    row: Int,
    column: Int,
    modifier: Modifier = Modifier,
) {
    val focused = tab.focusedPaneId == pane.id
    // Geometry is dropped when the pane leaves the grid, which also aborts a drag of that pane: a
    // pane can go away from outside its own header (closing its tab, locking the vault), and the
    // gesture coroutine dies with it without an onDragEnd to clear the indicator.
    DisposableEffect(pane.id) { onDispose { drag.paneClosed(pane.id) } }
    // A pane that draws no live terminal (blank, connecting, failed, or a frozen dead session) never
    // claims the keyboard ([TerminalScreen] requests focus only for a live, focused one). Focusing it
    // must still take the keyboard away from the pane that had it — otherwise the accent border, the
    // tab chip and the snippet target move here while typing keeps going into a sibling's live shell.
    // The empty pane itself holds the focus (rather than it being cleared), so the grid's key handler
    // still sees the arrow chord and the user can move on to a pane that does have a shell.
    // Keyed on takesKeyboard too: a focused pane whose session drops keeps its id and stays focused,
    // but its live [TerminalScreen] is composed from another branch and dies with the keyboard focus,
    // and the frozen one that replaces it never claims it back. Without this key nothing inside the
    // grid holds focus after a drop, and the arrow chord goes dead until the user clicks a pane.
    val paneFocus = remember { FocusRequester() }
    val takesKeyboard = pane.controller.uiState is ConnectionUiState.Connected
    // Not while something modal is up: a session dropping under the connect-password dialog would
    // otherwise take the caret out of its field (the same rule [ClaimKeyboard] follows). Watched
    // through a snapshot flow rather than read in composition, so a modal opening does not
    // invalidate every pane of the grid.
    LaunchedEffect(focused, takesKeyboard) {
        if (!focused || takesKeyboard) return@LaunchedEffect
        snapshotFlow { ModalPresence.openCount }.first { it == 0 }
        paneFocus.requestFocus()
    }
    Column(
        modifier
            .paneBoundsAnchor(drag, pane.id, row, column)
            .focusPaneOnPress(sessions, tab.id, pane.id)
            .focusRequester(paneFocus)
            .focusable(),
    ) {
        // A single-pane tab has nothing to tell apart and no header of its own: the work bar names
        // it, and the pane starts at the terminal. Only a split grid needs a header per pane.
        if (tab.isSplit) {
            PaneHeader(sessions, tab, pane, state, drag, focused)
            HLine()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LivePaneBody(state, pane, solo = !tab.isSplit, modifier = Modifier.fillMaxSize(), tabId = tab.id, focused = focused)
            drag.drop?.takeIf { it.overPaneId == pane.id }?.let { PaneDropIndicator(it.edge) }
        }
    }
}

/**
 * A pane's header on a split grid: status dot, host label, and the "⋮" menu that re-points or closes
 * it. Dragging the header moves the pane to another slot; the drag only claims the pointer past a
 * dead zone, so the picker still opens on a click.
 *
 * The address is left to the work bar, which lists every host of the tab: four panes side by side
 * make the strip narrow, and a host name that ellipsises into `prod-w…` says less than nothing.
 * [focused] marks the pane the keyboard is in with an accent strip on the header's leading edge.
 */
@Composable
internal fun PaneHeader(
    sessions: SessionsController,
    tab: Tab,
    pane: Session,
    state: DesktopDesignState,
    drag: PaneDragState,
    focused: Boolean,
) {
    val mono = LocalFonts.current.mono
    val connectPane = LocalConnectPane.current
    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // A pane that already holds a session is re-pointed only after a confirmation: the old
    // connection goes down with it, and the header is one stray click away from the host list. An
    // empty pane has nothing to lose, so it connects straight away.
    val pick: (Host) -> Unit = { host ->
        if (pane.isBlank) connectPane(host, pane.id) else state.requestPaneConnect(tab.id, pane.id, host)
        pickerOpen = false
    }
    Box(Modifier.fillMaxWidth().background(Skerry.colors.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PANE_HEADER_HEIGHT)
                .draggablePaneHeader(drag, pane.id) { slot -> sessions.movePane(tab.id, pane.id, slot) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The focus marker is the strip rather than a border around the whole pane: on a grid of
            // four the borders met in the middle and it took a second look to see which one was lit.
            Box(Modifier.width(2.dp).fillMaxHeight().background(if (focused) Skerry.colors.teal else Color.Transparent))
            Row(
                // The split tab's own host picker, same as the work bar's on a solo tab: the press
                // takes the keyboard and the popup claims nothing back.
                Modifier.weight(1f).fillMaxHeight().handsKeyboardBack()
                    .clickable { pickerOpen = !pickerOpen }.padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(sessionDotColor(pane.status))
                if (pane.isBlank) {
                    Txt(stringResource(Res.string.term_select_host_placeholder), color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Txt(
                        pane.title,
                        color = if (focused) Skerry.colors.text else Skerry.colors.dim,
                        size = 11.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Box {
                IconBtn("more_vert", onClick = { menuOpen = !menuOpen }, box = 20, icon = 13.sp, tint = Skerry.colors.faint, tooltip = stringResource(Res.string.term_pane_menu))
                if (menuOpen) PaneMenu(onDismiss = { menuOpen = false }, onChangeHost = { pickerOpen = true }) {
                    // Closing a pane that holds a session is confirmed (its connection goes with
                    // it); an empty one has nothing to lose and closes straight away.
                    if (pane.isBlank) sessions.closePane(tab.id, pane.id) else state.requestClosePane(tab.id, pane.id)
                }
            }
        }
        if (pickerOpen) {
            Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                PaneHostPicker(onPick = pick)
            }
        }
    }
}

/** What can be done to one pane of a split: point it at another host, or close it. */
@Composable
internal fun PaneMenu(onDismiss: () -> Unit, onChangeHost: () -> Unit, onClose: () -> Unit) {
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        // A focusable popup owns the keyboard while it is up: registered so the session it opened
        // over does not claim it back and close the menu from under the pointer.
        rememberModalPresence()
        MenuPanel(Modifier.padding(top = PANE_HEADER_HEIGHT)) {
            MenuActionRow("dns", stringResource(Res.string.term_pane_change_host)) { onDismiss(); onChangeHost() }
            MenuActionRow("close", stringResource(Res.string.term_pane_close)) { onDismiss(); onClose() }
        }
    }
}

/**
 * Host picker from the catalog ([LocalHosts]): clicking a host hands it to [onPick], which either
 * connects it into the pane or asks first (see the call site). Empty outside the vault gate (no
 * live catalog).
 */
@Composable
internal fun PaneHostPicker(onPick: (Host) -> Unit) {
    val mono = LocalFonts.current.mono
    // Terminal profiles only: a pane is a shell, and a remote desktop picked here would be dialled
    // as SSH on its RFB port.
    val hosts = LocalHosts.current?.hosts?.inSection(HostSection.Terminal) ?: emptyList()
    // Wider than a menu measures and taller than it fits: a catalog is a list, not a set of verbs.
    // The scroll is inside the panel rather than around it, so the frame bounds the viewport instead
    // of sliding away with the rows.
    MenuPanel(width = PANE_PICKER_WIDTH) {
        Column(Modifier.heightIn(max = PANE_PICKER_HEIGHT).verticalScroll(rememberScrollState())) {
            if (hosts.isEmpty()) {
                Txt(stringResource(Res.string.term_no_hosts_in_catalog), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
            hosts.forEach { host ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .handsKeyboardBack().clickable { onPick(host) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("dns", size = 14.sp, color = Skerry.colors.cyanBright)
                    Txt(host.rowLabel(), color = Skerry.colors.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal val PANE_PICKER_WIDTH = 240.dp
internal val PANE_PICKER_HEIGHT = 280.dp

/**
 * Divider between two panes: a hairline drawn inside a wider grip, so it can be grabbed without
 * aiming at a single pixel. Dragging it hands the travel (in pixels) to the caller, which turns it
 * into a share of the grid.
 */
@Composable
private fun PaneDivider(vertical: Boolean, onDrag: (Float) -> Unit) {
    val dragState = rememberDraggableState { onDrag(it) }
    // The hairline is easy to miss, so the cursor is what announces the divider: it turns into the
    // resize arrow of the axis it moves on as soon as the pointer is over the grip.
    val cursor = remember(vertical) { paneResizeCursor(vertical) }
    if (vertical) {
        Box(
            Modifier.width(PANE_DIVIDER_GRIP).fillMaxHeight()
                .pointerHoverIcon(cursor)
                .draggable(dragState, Orientation.Horizontal),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(1.dp).fillMaxHeight().background(Skerry.colors.cyan14)) }
    } else {
        Box(
            Modifier.height(PANE_DIVIDER_GRIP).fillMaxWidth()
                .pointerHoverIcon(cursor)
                .draggable(dragState, Orientation.Vertical),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.height(1.dp).fillMaxWidth().background(Skerry.colors.cyan14)) }
    }
}

/** Where a dragged pane would land: an accent bar on the edge of the pane under the pointer. */
@Composable
private fun BoxScope.PaneDropIndicator(edge: PaneEdge) {
    val bar = when (edge) {
        PaneEdge.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp)
        PaneEdge.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
        PaneEdge.Left -> Modifier.align(Alignment.CenterStart).fillMaxHeight().width(3.dp)
        PaneEdge.Right -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp)
    }
    Box(bar.background(Skerry.colors.cyanBright))
}

/** Both panes of the design preview: the mock terminal and, behind the demo flag, a second one. */
@Composable
internal fun ColumnScope.MockPanes(state: DesktopDesignState) {
    Row(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Headers only on the split, the same rule the live grid follows.
            if (state.split) {
                MockPaneHeader("root@prod-web-01", focused = true)
                HLine()
            }
            MockTerminalPane(state, Modifier.weight(1f).fillMaxWidth())
        }
        if (state.split) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Skerry.colors.cyan14))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                MockPaneHeader("root@db-master", focused = false)
                HLine()
                SplitPane(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * Intercepts press in [PointerEventPass.Initial] (without consuming it): focuses pane [paneId] of
 * tab [tabId], so the tab chip title and everything else that follows the focused pane keep up with
 * where the user is working. Keyboard routing stays with [TerminalScreen] (its own focusRequester on
 * pointer-down).
 */
private fun Modifier.focusPaneOnPress(sessions: SessionsController, tabId: String, paneId: String): Modifier =
    this.pointerInput(sessions, tabId, paneId) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) sessions.focusPane(tabId, paneId)
            }
        }
    }
