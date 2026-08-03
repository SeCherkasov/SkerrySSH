package app.skerry.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.session.SessionsController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_lock
import app.skerry.ui.generated.resources.shtail_new_tab
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.BrandMark
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.SessionDot
import app.skerry.ui.design.Dot
import app.skerry.ui.design.IconBtn
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.Sym
import app.skerry.ui.session.TabDragState
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.session.tabBoundsAnchor
import app.skerry.ui.host.HostSection
import app.skerry.ui.session.draggableTab
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.isProdHostId

@Composable
internal fun TitleBar(state: DesktopDesignState, onLock: (() -> Unit)?, windowChrome: WindowChrome? = null) {
    // With custom chrome, the titlebar doubles as the window-drag area (the OS titlebar is gone).
    if (windowChrome != null) windowChrome.dragArea { TitleBarRow(state, onLock, windowChrome) }
    else TitleBarRow(state, onLock, windowChrome = null)
}

@Composable
private fun TitleBarRow(state: DesktopDesignState, onLock: (() -> Unit)?, windowChrome: WindowChrome?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TITLEBAR_HEIGHT)
            .background(Brush.verticalGradient(listOf(Skerry.colors.titleTop, Skerry.colors.titleBottom)))
            .padding(start = 14.dp, end = if (windowChrome != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            // Consume presses so the titlebar's double-click-to-maximize (and window drag) treats
            // the brand mark like a button, not empty titlebar space — clicking the logo must not
            // toggle maximize.
            Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            BrandMark(size = 28.dp)
            Txt("Skerry", color = Skerry.colors.text, size = 14.5.sp, weight = FontWeight.Bold, letterSpacing = (-0.2).sp)
        }
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Live tabs from the session manager (behind the vault gate); otherwise mock tabs.
            val sessions = LocalSessions.current
            // The localized label for a new blank tab is resolved here (composable side): stringResource
            // isn't available in SessionsController, so the label is passed into openBlank.
            val newTabTitle = stringResource(Res.string.shtail_new_tab)
            if (sessions != null) {
                // Tab drag-reorder state: dragging chips to swap places.
                val tabDrag = remember { TabDragState() }
                // rememberUpdatedState: pointerInput is only recreated by the tabId key, so the ids()
                // lambda must read the fresh list via .value, otherwise onDragEnd would use a stale
                // order (same as done for host drag).
                val tabIds = rememberUpdatedState(sessions.tabs.map { it.id })
                sessions.tabs.forEachIndexed { index, s ->
                    // Insert line before the chip the dragged tab is currently hovering over.
                    if (tabDrag.insertLineIndex == index) TabInsertLine()
                    // On a split tab the chip shows the focused pane: the name changes as focus
                    // moves between panes.
                    val focused = s.focusedPane
                    // A production session paints its chip sunset (strip/border/tint) — the tab row
                    // is where a wrong-window mistake starts, so the marker sits there too.
                    val prodTab = isProdHostId(focused.hostId)
                    SessionTabChip(
                        name = focused.tabTitle(state.settings.showTerminalTitleOnTabs),
                        // A recording tab has no connection: its dot and accent are sunset, so it
                        // never reads as a live (or dead) session.
                        dot = if (s.isPlayer) Skerry.colors.sunset else sessionDotColor(focused.status),
                        accent = if (s.isPlayer || prodTab) Skerry.colors.sunset else Skerry.colors.cyan,
                        split = s.isSplit,
                        active = s.id == sessions.activeId,
                        // Chips of both sections share one row: selecting one swaps the work area
                        // (workAreaSection) and leaves the rail on whatever catalog is open.
                        onClick = { sessions.activate(s.id) },
                        onClose = { tabDrag.tabClosed(s.id); sessions.close(s.id) },
                        dragging = tabDrag.draggingTabId == s.id,
                        modifier = Modifier
                            .tabBoundsAnchor(tabDrag, s.id)
                            .draggableTab(tabDrag, s.id, ids = { tabIds.value }) { from, to -> sessions.moveTab(from, to) },
                    )
                }
                // Insert line at the very end of the row (moving a tab to the tail).
                if (tabDrag.insertLineIndex == sessions.tabs.size) TabInsertLine()
            } else {
                state.tabs.forEachIndexed { i, tab ->
                    SessionTabChip(tab.name, tab.dot.tint(), active = i == state.activeTab, onClick = { state.setTab(i) }, onClose = { state.closeTab(i) })
                }
            }
            // "+" creates a BLANK tab with no session (live mode) and switches to its terminal
            // placeholder; the first connect from the sidebar fills it in ([SessionsController.connect]).
            // In mock/preview (no live sessions), keep the old behavior — open the modal.
            IconBtn(
                "add",
                onClick = {
                    if (sessions != null) {
                        // A new blank tab starts on the Terminal sub-view (Session.view's default),
                        // so the work area moves to the terminal section to show its placeholder.
                        sessions.openBlank(newTabTitle)
                        state.showSection(HostSection.Terminal)
                    } else {
                        state.openModal()
                    }
                },
                box = 26,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Skerry.colors.cyan08)
                    .border(1.dp, Skerry.colors.cyan20, RoundedCornerShape(6.dp))
                    .clickable(onClick = onLock ?: state::lock)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("lock_open", size = 14.sp, color = Skerry.colors.cyan)
                Txt(stringResource(Res.string.shell_lock), color = Skerry.colors.cyan, size = 11.sp, weight = FontWeight.Medium)
            }
            if (windowChrome != null) WindowButtons(windowChrome, Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * A session tab as a segmented pill with editor-style selection: active — a thin cyan strip on the top
 * edge + cyan background with bright text; hovered inactive — a slightly lighter background; resting — a
 * muted translucent pill with dim text. A connection status dot on the left. The close cross shows only
 * on the active or hovered tab; others reserve the space with an empty box so text doesn't jump when the
 * cross appears.
 */
/** Demo-tab status dot color from the active theme (the state layer stores only the semantic [SessionDot]). */
@Composable
private fun SessionDot.tint(): Color = when (this) {
    SessionDot.On -> Skerry.colors.moss
    SessionDot.Warn -> Skerry.colors.amber
    SessionDot.Off -> Skerry.colors.faint
}

@Composable
internal fun SessionTabChip(
    name: String,
    dot: Color,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    split: Boolean = false,
    dragging: Boolean = false,
    // Chip accent (strip/border/background tint). Sessions use cyan; a recording tab is sunset, so a
    // replay is never mistaken for a live shell at a glance.
    accent: Color = Skerry.colors.cyan,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    // Shared interactionSource: clickable emits hover events that collectIsHoveredAsState reads.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showClose = active || hovered
    // pointerInput(Unit) below outlives recompositions; read the fresh onClose through state so a
    // reordered tab list doesn't close via a stale lambda.
    val close = rememberUpdatedState(onClose)
    // Accent tints: the same 10%/20% steps the cyan tokens use, so a non-default accent keeps the
    // chip's weight instead of turning into a solid block.
    val accentBg = accent.copy(alpha = 0.10f)
    val accentBorder = accent.copy(alpha = 0.20f)
    Row(
        modifier
            // Dim a dragged chip (alpha) so it reads as "lifted" out of the row.
            .alpha(if (dragging) 0.5f else 1f)
            .height(28.dp)
            .clip(shape)
            .background(
                when {
                    active -> accentBg
                    hovered -> Skerry.colors.hover
                    else -> Skerry.colors.card
                },
            )
            .border(1.dp, if (active) accentBorder else Skerry.colors.line, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // Middle-click closes the tab (browser-tab convention), active or not: armed on the
            // tertiary press, committed on its release while still over the chip — moving off
            // before releasing aborts an accidental wheel-button bump, like browsers do. Raw event
            // observation like HostsSidebar's double-click: clickable only reacts to the primary
            // button, so the tertiary press is never consumed by it or by the ✕ IconBtn below.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var armed = false
                    while (true) {
                        val e = awaitPointerEvent()
                        when {
                            e.type == PointerEventType.Press && e.buttons.isTertiaryPressed -> armed = true
                            e.type == PointerEventType.Release && armed && !e.buttons.isTertiaryPressed -> {
                                armed = false
                                val p = e.changes.first().position
                                val inside = p.x >= 0f && p.y >= 0f && p.x < size.width && p.y < size.height
                                if (inside) close.value()
                            }
                        }
                    }
                }
            }
            .padding(start = 11.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        // Split marker: the tab holds two panes.
        if (split) Sym("splitscreen_right", size = 13.sp, color = if (active) accent else Skerry.colors.faint)
        Txt(
            name,
            color = if (active) Skerry.colors.text else Skerry.colors.dim,
            size = 12.sp,
            weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (showClose) {
            IconBtn("close", onClick = onClose, box = 16, icon = 14.sp, tint = if (active) Skerry.colors.dim else Skerry.colors.faint)
        } else {
            Box(Modifier.width(16.dp))
        }
    }
}

/** Vertical insertion-position indicator during tab drag-reorder (cyan accent). */
@Composable
private fun TabInsertLine() {
    Box(Modifier.width(2.dp).height(22.dp).clip(RoundedCornerShape(1.dp)).background(Skerry.colors.cyan))
}
