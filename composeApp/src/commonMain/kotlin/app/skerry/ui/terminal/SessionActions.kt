package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.LocalSessionShare
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_toolbar_tip
import app.skerry.ui.generated.resources.share_session
import app.skerry.ui.generated.resources.shell_tip_add_pane
import app.skerry.ui.generated.resources.shell_tip_assistant
import app.skerry.ui.generated.resources.shell_tip_disconnect
import app.skerry.ui.generated.resources.shell_tip_files
import app.skerry.ui.generated.resources.shell_tip_monitor
import app.skerry.ui.generated.resources.shell_tip_more_actions
import app.skerry.ui.generated.resources.shell_tip_play
import app.skerry.ui.generated.resources.shell_tip_record
import app.skerry.ui.generated.resources.shell_tip_snippets
import app.skerry.ui.generated.resources.shell_tip_sync_panes
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.runbook.RunbookPaletteButton
import app.skerry.ui.session.SessionView
import app.skerry.ui.share.ShareSessionButton
import app.skerry.ui.share.shareableTeams
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What an action is about, and where the row's separators fall: [Workspace] shapes what the tab
 * holds, [Session] does something to the shell running in it, [Global] belongs to the app rather
 * than to any one session. Declared in row order.
 */
internal enum class ToolbarGroup { Workspace, Session, Global }

/**
 * One entry of the session action row. A narrow window narrows the row, so when the icons stop
 * fitting the ones listed here give way in this order — the rarely-reached first, the ones a session
 * is steered with last. [Sync], [AddPane] and [Disconnect] are not in the list: they never overflow.
 *
 * [group] places the action between the row's separators; [needsSession] marks the ones that act on
 * a live shell and are simply not drawn without one.
 */
internal enum class ToolbarAction(val group: ToolbarGroup, val needsSession: Boolean) {
    Play(ToolbarGroup.Global, needsSession = false),
    Record(ToolbarGroup.Session, needsSession = true),
    Share(ToolbarGroup.Session, needsSession = true),
    Runbook(ToolbarGroup.Session, needsSession = true),
    Snippets(ToolbarGroup.Session, needsSession = true),
    Monitor(ToolbarGroup.Workspace, needsSession = true),
    Files(ToolbarGroup.Workspace, needsSession = true),
}

/**
 * The row, left to right: groups in order, actions in the order they are drawn inside one. The
 * enum's own order is the overflow priority (rarest first) and says nothing about placement, so
 * the row's order has to be written down separately — the row draws it by hand, and
 * [OverflowActionsButton] sorts the menu by it so a hidden action keeps its place.
 */
internal val TOOLBAR_ROW_ORDER: List<ToolbarAction> = listOf(
    ToolbarAction.Files,
    ToolbarAction.Monitor,
    ToolbarAction.Snippets,
    ToolbarAction.Runbook,
    ToolbarAction.Share,
    ToolbarAction.Record,
    ToolbarAction.Play,
)

/**
 * Which actions the row draws at all. Without a session the ones that steer one are left out
 * rather than dimmed: a button that cannot do anything is worse than no button. What remains is
 * the recording player, which opens a recording in a tab of its own.
 */
internal fun availableActions(hasSession: Boolean, monitorShown: Boolean = true): Set<ToolbarAction> =
    ToolbarAction.entries.filterTo(mutableSetOf()) {
        (hasSession || !it.needsSession) && (monitorShown || it != ToolbarAction.Monitor)
    }

/** Width one icon claims in the row: the button box plus the spacing in front of it. */
private val ACTION_SLOT_WIDTH = 30.dp

/** Width a group separator claims: the 1dp hairline, its 2×4dp air, and the row gap it adds. */
private val SEPARATOR_SLOT_WIDTH = 11.dp

/**
 * Whether [group] has anything on its side of a separator: an action the row still draws ([drawn]),
 * or one of the buttons that never overflow and so are not in [ToolbarAction] — the sync toggle and
 * add-pane sit in [ToolbarGroup.Workspace], the power button and the assistant in
 * [ToolbarGroup.Global], and all of those come with a session. A separator needs both of its sides:
 * with a whole group overflowed into the menu two hairlines would otherwise land side by side.
 */
private fun groupHasContent(group: ToolbarGroup, hasSession: Boolean, drawn: (ToolbarAction) -> Boolean): Boolean =
    ToolbarAction.entries.any { it.group == group && drawn(it) } ||
        (hasSession && (group == ToolbarGroup.Workspace || group == ToolbarGroup.Global))

/** How many hairlines the row draws for a given set of visible actions. */
private fun separatorCount(hasSession: Boolean, drawn: (ToolbarAction) -> Boolean): Int =
    (ToolbarGroup.entries.count { groupHasContent(it, hasSession, drawn) } - 1).coerceAtLeast(0)

/**
 * Room the work bar keeps for its own title. Enough for the host label, its address and the status
 * dot — the row gives way into its overflow menu before the bar stops saying what is open, since
 * that is what the title is there for.
 */
private val WORK_BAR_TITLE_ROOM = 240.dp

/**
 * Width the bar spends on itself before either the title or the actions get any: its horizontal
 * padding (2×10), the sidebar chevron (26) and the two 8dp gaps around the title. [available] is the
 * whole work area, so this comes off the top — the row used to float over a pane and had none of it.
 */
private val WORK_BAR_CHROME = 62.dp

/**
 * Session action icons (sync / add pane / SFTP / monitor / snippets / runbooks / sharing /
 * recording / player / assistant / disconnect), filling the right end of the [WorkBar].
 *
 * [available] is the width of the work area the bar spans, or `null` when it cannot be measured
 * yet. Once the icons no longer fit beside the bar's own title they collapse into an overflow menu,
 * in the order of [ToolbarAction].
 */
@Composable
internal fun RowScope.SessionActions(
    state: DesktopDesignState,
    available: Dp?,
    assistantShown: Boolean,
) {
    val sessions = LocalSessions.current
    val tab = sessions?.activeTerminal
    // Session-scoped actions (snippets, runbooks, recording) act on the pane the user is working
    // in, not on the tab's first pane — on a split those are different sessions. Tab-scoped ones
    // (the sync/add-pane toggles and the power button) keep using the tab itself.
    val active = tab?.focusedPane
    val teams = LocalTeams.current
    // Non-null only on a split tab, which is the only place the sync toggle is drawn.
    val syncTab = tab?.takeIf { it.isSplit }
    // The static preview has no session manager at all and is meant to show the full row; a live
    // window with no terminal tab in focus has nothing for the session-scoped actions to act on —
    // every one of them is bound to `active`, which is null in exactly that state.
    val hasSession = sessions == null || tab != null
    val monitorShown = monitorAvailable(
        hasSession = tab != null,
        watched = active?.controller?.isWatched == true,
        mock = sessions == null,
    )
    val shown = availableActions(hasSession, monitorShown)
    val hidden = overflowedActions(
        available,
        syncShown = syncTab != null,
        assistantShown = assistantShown,
        hasSession = hasSession,
        monitorShown = monitorShown,
    )
    val drawn = { action: ToolbarAction -> action in shown && action !in hidden }
    val groupDrawn = { group: ToolbarGroup -> groupHasContent(group, hasSession, drawn) }

    // Files and the monitor are stateless view switches, so the overflow menu can run them
    // directly. The palettes and the recorder own their popups and save dialogs, so those are
    // parked below instead and reached through the request signals they already listen on.
    val openSftp = {
        if (sessions != null) { state.clearOverlay(); sessions.setActiveView(SessionView.Sftp) } else state.showView(DesktopView.Sftp)
    }
    val openMonitor = {
        if (sessions != null) {
            state.clearOverlay(); sessions.setActiveView(SessionView.Monitor)
        } else {
            state.showView(DesktopView.Monitor)
        }
    }
    val playerTabTitle = stringResource(Res.string.term_player_title)
    val onCastOpened: (CastOpenResult) -> Unit = { result ->
        if (result is CastOpenResult.Loaded && sessions != null) {
            state.clearOverlay()
            // The file name labels the tab: it says "recording", and two recordings of the same
            // host stay apart (their in-file titles are both just the host name).
            sessions.openPlayer(result.fileName.ifBlank { playerTabTitle }, result.cast)
        } else {
            state.showCast(result)
        }
    }

    // Group 1 — the workspace: what the tab holds and which of its views is on screen.
    // Synchronized input: typing in one pane reaches every connected pane of this tab. Lit while on,
    // since it changes where every keystroke goes. Shown only once the tab is actually split — with
    // a single pane there is nothing to synchronize it with.
    if (syncTab != null) {
        IconBtn(
            "sync_alt",
            onClick = { sessions.toggleSyncInput(syncTab.id) },
            tint = if (syncTab.syncInput) Skerry.colors.cyanBright else Skerry.colors.dim,
            tooltip = stringResource(Res.string.shell_tip_sync_panes),
        )
    }
    // Add pane: live mode puts another independent session on the active tab's grid (up to
    // MAX_PANES); mock/preview toggles the demo split. Dimmed and inert once the tab is full — the
    // same treatment the info button gets when there is nothing for it to open.
    val canAddPane = tab?.layout?.isFull != true && tab?.isPlayer != true
    if (hasSession) {
        IconBtn(
            "splitscreen_right",
            onClick = { if (sessions == null) state.toggleSplit() else if (canAddPane) sessions.addPane() },
            tint = if (canAddPane) Skerry.colors.dim else Skerry.colors.faint,
            tooltip = stringResource(Res.string.shell_tip_add_pane),
        )
    }
    // Switches the active tab's subview (live mode, plus overlay reset) / mock fallback.
    if (drawn(ToolbarAction.Files)) {
        IconBtn("folder", onClick = openSftp, tooltip = stringResource(Res.string.shell_tip_files))
    }
    // The host monitor, the second view this tab can hold: resources of the machine the shell runs
    // on, one exec round-trip per poll.
    if (drawn(ToolbarAction.Monitor)) {
        IconBtn("monitoring", onClick = openMonitor, tooltip = stringResource(Res.string.shell_tip_monitor))
    }

    // Group 2 — what can be sent into the session running there.
    ActionGroupSeparator(shown = groupDrawn(ToolbarGroup.Workspace) && groupDrawn(ToolbarGroup.Session))
    // Quick snippet launch into the active session without leaving for the Snippets section.
    if (drawn(ToolbarAction.Snippets)) SnippetPaletteButton(active, state.snippetPaletteRequests)
    // Same idea one size up: start a saved procedure here instead of going to its section.
    if (drawn(ToolbarAction.Runbook)) RunbookPaletteButton(active, state.runbookPaletteRequests)
    // Streams this session to a team over the sync relay (viewers watch; the host decides whether
    // they may type).
    if (drawn(ToolbarAction.Share)) {
        ShareSessionButton(active, LocalSessionShare.current, shareableTeams(), state.sharePanelRequests)
    }
    // Asciinema recording of this session; the stop click offers a Save-As for the .cast.
    if (drawn(ToolbarAction.Record)) {
        RecordSessionButton(
            active,
            state.recordingToggleRequests,
            onSaved = { hostId, seconds -> teams?.reportSessionRecorded(hostId, seconds) },
        ) { state.showRecordingNotice(it) }
    }

    // Group 3 — the app around the session: sections that stand on their own, and the way out.
    ActionGroupSeparator(
        shown = (groupDrawn(ToolbarGroup.Workspace) || groupDrawn(ToolbarGroup.Session)) && groupDrawn(ToolbarGroup.Global),
    )
    // Plays a .cast back. Not tied to a session (a recording is watched, not run), which is why it
    // sits here rather than behind a connected-only guard. Live mode opens the recording in its own
    // tab, so the shells stay reachable while it plays; the mock path (no session manager) has no
    // tabs and falls back to the overlay.
    if (drawn(ToolbarAction.Play)) PlayRecordingButton(state.castOpenRequests, onCastOpened)
    // Opens the assistant beside the terminal. Lit while it is open, like the info toggle; absent
    // entirely when AI is off for this host or globally, so a host that opted out shows no AI
    // affordance at all.
    if (assistantShown) {
        IconBtn(
            "auto_awesome",
            onClick = state::toggleAssistant,
            tint = if (state.assistantPanel) Skerry.colors.teal else Skerry.colors.dim,
            tooltip = stringResource(Res.string.shell_tip_assistant),
        )
    }
    if (hidden.isNotEmpty()) {
        OverflowActionsButton(hidden, state, tabKey = tab?.id, onOpenSftp = openSftp, onOpenMonitor = openMonitor)
    }
    // Power: closes the active session (live path) with a confirmation prompt (destructive, no
    // auto-reconnect); no-op stub in mock mode. With no session open there is nothing to close.
    if (hasSession) {
        IconBtn(
            "power_settings_new",
            onClick = { if (tab != null) state.requestCloseSession(tab.id) },
            tint = Skerry.colors.sunset,
            tooltip = stringResource(Res.string.shell_tip_disconnect),
        )
    }
    // Parked out of sight, still in composition: these buttons own the palettes, the recorder and
    // the file pickers behind them, and dropping them from the tree would take that state with them
    // — the overflow menu drives them through their request signals instead.
    Box(Modifier.size(0.dp).clipToBounds()) {
        if (ToolbarAction.Snippets in hidden) SnippetPaletteButton(active, state.snippetPaletteRequests)
        if (ToolbarAction.Runbook in hidden) RunbookPaletteButton(active, state.runbookPaletteRequests)
        if (ToolbarAction.Record in hidden) {
            RecordSessionButton(
                active,
                state.recordingToggleRequests,
                onSaved = { hostId, seconds -> teams?.reportSessionRecorded(hostId, seconds) },
            ) { state.showRecordingNotice(it) }
        }
        if (ToolbarAction.Play in hidden) PlayRecordingButton(state.castOpenRequests, onCastOpened)
        if (ToolbarAction.Share in hidden) {
            ShareSessionButton(active, LocalSessionShare.current, shareableTeams(), state.sharePanelRequests)
        }
    }
}

/**
 * Hairline between two action groups. Drawn only while both sides of it exist ([shown]) — with no
 * session the row is one group, and a separator with nothing before it would read as a border.
 */
@Composable
private fun ActionGroupSeparator(shown: Boolean) {
    if (!shown) return
    VLine(Skerry.colors.line, Modifier.padding(horizontal = 4.dp).height(SEPARATOR_HEIGHT))
}

/** How tall the group separator stands inside the bar — shorter than the icons it parts. */
private val SEPARATOR_HEIGHT = 16.dp

/**
 * Whether the monitor has anything to show about the pane in focus. Every number on that screen —
 * host profile, cipher, uptime, live metrics — comes from a connection this app owns, so a pane
 * merely watching a colleague's shared session ([watched]) doesn't get the button at all, rather
 * than one that opens a screen of dashes. [mock] is the preview path with no session backend,
 * where the static layout is the point.
 */
internal fun monitorAvailable(hasSession: Boolean, watched: Boolean, mock: Boolean): Boolean =
    if (mock) true else hasSession && !watched

/**
 * Which actions have to leave the row for it to fit beside the work bar's title. [available] is the
 * width of the bar (`null` = not measured yet, so nothing overflows), and [syncShown] counts the
 * sync toggle, which is only there on a split tab.
 *
 * Pure so the thresholds can be tested without a window: the row must also keep room for the
 * overflow button itself once anything is hidden.
 */
internal fun overflowedActions(
    available: Dp?,
    syncShown: Boolean,
    assistantShown: Boolean = false,
    hasSession: Boolean = true,
    monitorShown: Boolean = true,
): Set<ToolbarAction> {
    if (available == null) return emptySet()
    // Only what the row actually draws can overflow: without a session the session-scoped actions
    // were never there to hide (see availableActions).
    val order = ToolbarAction.entries.filter { it in availableActions(hasSession, monitorShown) }
    // + add-pane and power, which a session brings, plus the two conditional buttons when drawn.
    val fixed = (if (hasSession) 2 else 0) + (if (syncShown) 1 else 0) + if (assistantShown) 1 else 0
    val room = available - WORK_BAR_TITLE_ROOM - WORK_BAR_CHROME
    // Give way in enum order until the row fits, re-counting the separators at every step: a group
    // emptied into the menu stops drawing its hairline, and so stops costing its width too.
    for (drop in 0..order.size) {
        val kept = order.drop(drop).toSet()
        // One slot goes to the overflow button, from the moment there is anything in it.
        val icons = kept.size + fixed + if (drop > 0) 1 else 0
        val width = ACTION_SLOT_WIDTH * icons +
            SEPARATOR_SLOT_WIDTH * separatorCount(hasSession) { it in kept }
        if (width <= room) return order.take(drop).toSet()
    }
    return order.toSet()
}

/**
 * The "⋯" menu holding the actions that did not fit the row. [tabKey] closes it on a tab switch —
 * the row is one composable for every tab, so a menu left open would otherwise stay on screen and
 * quietly start acting on the tab that just became active.
 */
@Composable
private fun OverflowActionsButton(
    hidden: Set<ToolbarAction>,
    state: DesktopDesignState,
    tabKey: Any?,
    onOpenSftp: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    var open by remember(tabKey) { mutableStateOf(false) }
    Box {
        IconBtn("more_horiz", onClick = { open = !open }, tooltip = stringResource(Res.string.shell_tip_more_actions))
        if (open) {
            Popup(alignment = Alignment.TopEnd, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier
                        .padding(top = WORK_BAR_HEIGHT)
                        .width(220.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Skerry.colors.surface2)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                        .padding(4.dp),
                ) {
                    // Listed the way they sit in the row, so the menu reads as its continuation.
                    hidden.sortedBy { TOOLBAR_ROW_ORDER.indexOf(it) }.forEach { action ->
                        val run: () -> Unit = when (action) {
                            ToolbarAction.Files -> onOpenSftp
                            ToolbarAction.Monitor -> onOpenMonitor
                            ToolbarAction.Snippets -> state::requestSnippetPalette
                            ToolbarAction.Runbook -> state::requestRunbookPalette
                            ToolbarAction.Record -> state::requestRecordingToggle
                            ToolbarAction.Play -> state::requestCastOpen
                            ToolbarAction.Share -> state::requestSharePanel
                        }
                        MenuActionRow(icon = action.icon, label = stringResource(action.label)) {
                            open = false
                            run()
                        }
                    }
                }
            }
        }
    }
}

/** One line of a chrome menu: glyph, label, whole row clickable. */
@Composable
internal fun MenuActionRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.dim, size = 12.sp)
    }
}

/** The glyph the action carries in the row, reused by its overflow entry. */
private val ToolbarAction.icon: String
    get() = when (this) {
        ToolbarAction.Files -> "folder"
        ToolbarAction.Monitor -> "monitoring"
        ToolbarAction.Snippets -> "bolt"
        ToolbarAction.Runbook -> "checklist"
        ToolbarAction.Record -> "radio_button_checked"
        ToolbarAction.Play -> "play_circle"
        ToolbarAction.Share -> "cast"
    }

/** The action's own tooltip, reused as its label in the overflow menu. */
private val ToolbarAction.label: StringResource
    get() = when (this) {
        ToolbarAction.Files -> Res.string.shell_tip_files
        ToolbarAction.Monitor -> Res.string.shell_tip_monitor
        ToolbarAction.Snippets -> Res.string.shell_tip_snippets
        ToolbarAction.Runbook -> Res.string.runbook_toolbar_tip
        ToolbarAction.Record -> Res.string.shell_tip_record
        ToolbarAction.Share -> Res.string.share_session
        ToolbarAction.Play -> Res.string.shell_tip_play
    }
