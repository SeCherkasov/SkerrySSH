package app.skerry.ui.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.rowLabel
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.SnippetShortcut
import app.skerry.ui.terminal.CommandPalette
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.i18n.label
import app.skerry.ui.host.HostSection

/**
 * A pending connect waiting on a password (SSH host with no bound secret) — and at the same time
 * the delivery address for the resolved auth: a new tab, a specific tab's split pane, or running a
 * snippet command on the host.
 */
internal sealed interface PendingAuth {
    val host: Host

    /** Connect as a new tab (or into the active empty one). */
    data class NewTab(override val host: Host) : PendingAuth

    /** Connect into pane [paneId] of tab [tabId] (both fixed at the moment the host was chosen). */
    data class Pane(override val host: Host, val tabId: String?, val paneId: String) : PendingAuth

    /**
     * Open a session to the host and send [line] once connected — the fully resolved snippet
     * command line, newline included ([app.skerry.ui.snippet.SnippetManager.run] built it, so
     * dynamic variables are already confirmed and spliced in).
     */
    data class Snippet(override val host: Host, val line: String) : PendingAuth
}

/**
 * Global snippet hotkey: on KeyDown, serializes the chord ([SnippetShortcut]), looks up a snippet with
 * that hotkey and, if there's a connected session, runs its command in that session's terminal.
 * Returns `true` (event consumed) only on an actual run — otherwise the key falls through (to the
 * terminal, etc.).
 */
internal fun runSnippetHotkey(event: KeyEvent, manager: SnippetManager?, sessions: SessionsController?): Boolean {
    if (event.type != KeyEventType.KeyDown || manager == null) return false
    val combo = SnippetShortcut.format(
        event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
    ) ?: return false
    val entry = manager.forShortcut(combo) ?: return false
    val terminal = (sessions?.active?.focusedPane?.controller?.uiState as? ConnectionUiState.Connected)?.terminal ?: return false
    // Not gated on what a row shows: there is no row, the chord is one the user assigned to a record
    // of their own, and a confirmation on a key the user pressed to skip one is the wrong trade.
    manager.run(entry.id, recording = terminal.recording) { text, secrets -> terminal.sendUserInputGuarded(text, secrets) }
    return true
}

/**
 * Run a global shell hotkey ([matchDesktopShortcut]). Returns `true` if the action was applied
 * (consume the event), `false` if there's no target (e.g. Alt+digit past the tab count): the caller
 * then lets the key fall through (including to the snippet hotkey). Live mode addresses tabs via
 * [SessionsController]; mock/preview (no live sessions) uses the demo tabs in [DesktopDesignState].
 */
internal fun runDesktopShortcut(
    shortcut: DesktopShortcut,
    state: DesktopDesignState,
    sessions: SessionsController?,
    onLock: () -> Unit,
): Boolean {
    when (shortcut) {
        is DesktopShortcut.SelectTab -> return selectTabByIndex(shortcut.index, state, sessions)
        DesktopShortcut.NextTab -> return cycleTab(+1, state, sessions)
        DesktopShortcut.PrevTab -> return cycleTab(-1, state, sessions)
        // Opens the form of the section on screen: pressed over the desktops list it creates a
        // remote desktop, over the hosts list a shell.
        DesktopShortcut.NewConnection -> state.openModal(state.section)
        // A tab that is full, a remote desktop or a recording has nowhere to put another pane, and
        // the chord is still consumed: unlike the refusals below it fires over a live terminal, and
        // falling through would send Ctrl+Shift+D on to the shell as EOT and end the session. The
        // snippet binding it could otherwise have reached is marked reserved in the editor, which
        // warns rather than refuses — so a fall-through here is not certain to reach nothing.
        DesktopShortcut.AddPane -> if (sessions != null) sessions.addPane() else state.toggleSplit()
        DesktopShortcut.SyncPanes -> if (sessions != null) sessions.toggleSyncInput() else Unit
        // Handled by the pane grid itself ([paneGridDirection]), which sees the key only while the
        // keyboard is inside a pane — claiming it here would take the same chord away from every
        // text field, the file panel and a remote desktop.
        is DesktopShortcut.FocusPane -> return false
        DesktopShortcut.OpenSftp -> if (sessions != null) {
            state.clearOverlay(); sessions.setActiveView(SessionView.Sftp)
        } else {
            state.showView(DesktopView.Sftp)
        }
        // Search over the buffer of the pane the user is looking at (the focused one on a split).
        // With no terminal on screen there is nothing to search: fall through instead of no-oping,
        // so the chord can still reach a snippet binding — except over a remote desktop, where a
        // fall-through reaches no binding either (that needs a connected terminal too) and is typed
        // into the guest instead.
        DesktopShortcut.FindInTerminal -> {
            if (sessions?.activeDesktop != null) return true
            val session = sessions?.active ?: return false
            val terminal = paneTerminal(session.focusedPane.controller.uiState) ?: return false
            // The panel lives inside the terminal view, so bring that view up first — pressed over
            // SFTP or a recording, the chord would otherwise open a panel on a screen nobody sees.
            state.clearOverlay()
            sessions.setActiveView(SessionView.Terminal)
            terminal.search.open()
        }
        DesktopShortcut.Lock -> onLock()
        DesktopShortcut.Broadcast -> state.openBroadcast()
        // These two live in toolbar buttons that own their state; the shortcut nudges them, and asks
        // for nothing where there is no connected pane to nudge for — the button would refuse, and a
        // request nobody acts on is a chord spent for nothing. The ask outlives the frame it was
        // made in ([ToolbarRequest]), so the button reads it when it composes rather than having to
        // be composed already — which is why bringing that view up on the same line works at all.
        DesktopShortcut.SnippetPalette -> {
            if (!consumesSessionChord(sessions)) return false
            if (actsOnSessionChord(sessions)) { raiseToolbar(state, sessions); state.snippetPalette.raise() }
        }
        DesktopShortcut.ToggleRecording -> {
            if (!consumesSessionChord(sessions)) return false
            if (actsOnSessionChord(sessions)) { raiseToolbar(state, sessions); state.recordingToggle.raise() }
        }
        // Playback is the one of the set that needs no session, and its picker is driven by the
        // window chrome rather than by the toolbar — so it works over a remote desktop and over an
        // already-open recording, where there is no toolbar to bring up.
        DesktopShortcut.PlayRecording -> state.castOpen.raise()
        // Only over a live terminal: the palette inserts into it, so with nothing to insert into the
        // key falls through (to the snippet hotkey) instead of opening a dead-end overlay — and over
        // a remote desktop, where it cannot fall through, it opens nothing at all.
        DesktopShortcut.CommandPalette -> {
            if (!consumesSessionChord(sessions)) return false
            if (actsOnSessionChord(sessions)) {
                // The picked command lands on the pane's command line, so bring that view up first
                // — the same reason find and the assistant do it. Pressed over the file panel the
                // command would otherwise be typed where nobody is looking and found on the way
                // back.
                state.clearOverlay()
                sessions?.setActiveView(SessionView.Terminal)
                state.openCommandPalette()
            }
        }
        DesktopShortcut.OpenAssistant -> {
            // The panel lives beside the terminal, so bring that view up first — pressed over SFTP
            // or a recording the chord would otherwise open a panel nobody can see, and leave it
            // open for the next time the terminal comes back.
            state.clearOverlay()
            sessions?.setActiveView(SessionView.Terminal)
            state.openAssistant()
        }
    }
    return true
}

/**
 * Bring the terminal toolbar on screen for a chord that nudges one of its buttons. Those buttons are
 * drawn by the terminal view alone, so over the file panel, the monitor or a runbook run the ask
 * would otherwise wait for a toolbar nobody is looking at — the palette would open on the next
 * switch back instead of now. Find, the command palette and the assistant do the same.
 *
 * A recording is not in that list and cannot be: a player tab's pane never reaches Connected, so
 * [actsOnSessionChord] is already false there and `setActiveView` refuses such a tab outright.
 * Playback is the one chord of the set that still answers there, and it does so without a toolbar.
 *
 * Only ever reached with a connected pane in the active tab ([actsOnSessionChord]), which is what
 * makes the switch total: a remote-desktop or player tab has no connected pane and never gets here.
 */
private fun raiseToolbar(state: DesktopDesignState, sessions: SessionsController?) {
    state.clearOverlay()
    sessions?.setActiveView(SessionView.Terminal)
}

/**
 * Whether a chord that nudges a session button has something to act on — a connected pane, which is
 * the same terminal its button reads before drawing itself enabled.
 */
private fun actsOnSessionChord(sessions: SessionsController?): Boolean =
    sessions?.activeSession?.controller?.uiState is ConnectionUiState.Connected

/**
 * Whether the chord is consumed even where [actsOnSessionChord] says there is nothing to do. Two
 * cases: a remote-desktop tab, where the framebuffer holds the keyboard, so a chord let through is
 * not lost but typed into the guest; and the preview shell (no session manager at all), which draws
 * a terminal of its own for the same reason. Swallowing the key is the whole job there — the caller
 * still asks [actsOnSessionChord] before doing anything, because a panel that can reach no terminal
 * is a worse answer than a key that does nothing.
 */
private fun consumesSessionChord(sessions: SessionsController?): Boolean =
    sessions == null || sessions.activeDesktop != null || actsOnSessionChord(sessions)

/**
 * The terminal of a pane's connection state, or `null` if it has none. A dropped session keeps its
 * frozen screen ([ConnectionUiState.Disconnected]), and searching that output is exactly when it is
 * wanted — "what did that command print before the link died".
 */
private fun paneTerminal(state: ConnectionUiState?): TerminalScreenState? = when (state) {
    is ConnectionUiState.Connected -> state.terminal
    is ConnectionUiState.Disconnected -> state.terminal
    else -> null
}

/** Select a tab by 0-based index; `false` if no such tab exists (the key falls through). */
internal fun selectTabByIndex(index: Int, state: DesktopDesignState, sessions: SessionsController?): Boolean {
    if (sessions != null) {
        val target = sessions.tabs.getOrNull(index) ?: return false
        sessions.activate(target.id)
        return true
    }
    if (index !in state.tabs.indices) return false
    state.setTab(index)
    return true
}

/** Cyclically shift the active tab by [delta] (wrapping); `false` if there are no tabs. */
internal fun cycleTab(delta: Int, state: DesktopDesignState, sessions: SessionsController?): Boolean {
    if (sessions != null) {
        val list = sessions.tabs
        if (list.isEmpty()) return false
        val current = list.indexOfFirst { it.id == sessions.activeId }.coerceAtLeast(0)
        val next = ((current + delta) % list.size + list.size) % list.size
        sessions.activate(list[next].id)
        return true
    }
    val count = state.tabs.size
    if (count == 0) return false
    val next = ((state.activeTab + delta) % count + count) % count
    state.setTab(next)
    return true
}

/**
 * Connect to [host] with [auth]: if an empty ("+") tab is active — connect into it, otherwise a new
 * tab ([SessionsController.connect]). Then switch to the terminal (clearing the app overlay).
 * [jump] is the host's resolved ProxyJump chain (`null` — direct).
 */
internal fun openHostSession(
    sessions: SessionsController?,
    state: DesktopDesignState,
    host: Host,
    auth: SshAuth,
    jump: SshJump? = null,
    onConnected: ((app.skerry.ui.terminal.TerminalScreenState) -> Unit)? = null,
) {
    // Record the host in the sidebar's RECENT section (newest first, survives restart).
    state.recordRecentHost(host.id)
    sessions?.connect(
        hostId = host.id,
        title = host.rowLabel(),
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(jump),
        auth = auth,
        onConnected = onConnected,
    )
    // Live mode: the sub-view is held by the tab itself — showing the terminal section reveals it
    // (and clears any app overlay). Mock/preview (no sessions): fall back to Terminal via showView.
    if (sessions != null) state.showSection(HostSection.Terminal) else state.showView(DesktopView.Terminal)
}

/**
 * Connect [host] with [auth] into pane [paneId] of tab [tabId] (its own independent session).
 * No-op with no active tab. See [SessionsController.connectPane].
 */
internal fun openPaneSession(
    sessions: SessionsController?,
    state: DesktopDesignState,
    tabId: String?,
    paneId: String,
    host: Host,
    auth: SshAuth,
    jump: SshJump? = null,
) {
    if (sessions == null || tabId == null) return
    // Connecting into a pane is also a real connect to the host — record it in RECENT too.
    state.recordRecentHost(host.id)
    sessions.connectPane(
        tabId = tabId,
        paneId = paneId,
        hostId = host.id,
        title = host.rowLabel(),
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(jump),
        auth = auth,
    )
}
