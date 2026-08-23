package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.skerry.ui.session.SessionView
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.host.HostSection
import app.skerry.ui.known.KnownHostsView
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.metrics.MonitorView
import app.skerry.ui.sftp.SftpView
import app.skerry.ui.runbook.RunbookRunView
import app.skerry.ui.runbook.RunbooksView
import app.skerry.ui.snippet.SnippetsView
import app.skerry.ui.teams.TeamsView
import app.skerry.ui.terminal.CastPlayerView
import app.skerry.ui.terminal.TerminalView
import app.skerry.ui.tunnel.TunnelsView
import app.skerry.ui.vault.VaultView
import app.skerry.ui.vnc.RemoteDesktopsView
import app.skerry.ui.app.asSessionView
import app.skerry.ui.app.workAreaSection

/**
 * Switches the main content area. App-level views (Vault/Known/Teams/Snippets) render over
 * everything per [DesktopDesignState.appOverlay]; otherwise the selected tab decides
 * ([workAreaSection]): a remote-desktop tab renders the framebuffer, any other one renders its
 * subview ([app.skerry.ui.session.Session.view]). Only with no tab open does the section chosen in
 * the rail decide, on its empty state — with no live sessions at all (design preview) it also
 * supplies the subview via [state.view].
 *
 * The sidebar beside the work area is the rail's, not the tab's: it always lists
 * [DesktopDesignState.section]'s catalog.
 */
@Composable
fun Viewport(state: DesktopDesignState) {
    // Tagged with what it decided to draw: this `when` is the one place that knows, and a test
    // asking "which screen is up" has nowhere else to ask ([UiTags]).
    val overlay = state.appOverlay
    // This `when` is the definition of "app-level", not [DesktopView.isAppLevel] plus a second list
    // that has to agree with it: a value it does not name falls through to the work area, where the
    // rail button at least opens something. The two lists drifting apart drew an empty pane under a
    // correct screen tag, which is the one failure nothing reports.
    val appLevel: (@Composable () -> Unit)? = when (overlay) {
        DesktopView.Ports -> ({ TunnelsView() })
        DesktopView.Snippets -> ({ SnippetsView(state) })
        DesktopView.Runbooks -> ({ RunbooksView(state) })
        DesktopView.Vault -> ({ VaultView(state) })
        DesktopView.Known -> ({ KnownHostsView() })
        DesktopView.Teams -> ({ TeamsView() })
        // Spelled out rather than left to an `else`, so the compiler refuses a new DesktopView that
        // nothing here draws instead of letting it fall through silently.
        DesktopView.Terminal, DesktopView.Sftp, DesktopView.Monitor, null -> null
    }
    if (overlay != null && appLevel != null) {
        Box(Modifier.fillMaxSize().testTag(UiTags.screen(overlay))) { appLevel() }
        return
    }
    // overlay == null: renders the work area (showView only stores app-level values in appOverlay).
    val sessions = LocalSessions.current
    when (workAreaSection(sessions?.active, state.section)) {
        HostSection.RemoteDesktops -> Box(Modifier.fillMaxSize().testTag(UiTags.screen(HostSection.RemoteDesktops))) {
            RemoteDesktopsView(state)
        }
        HostSection.Terminal -> {
            // activeTerminal, not active: a remote-desktop tab renders in the branch above.
            val view = sessions?.activeTerminal?.view ?: state.view.asSessionView()
            Box(Modifier.fillMaxSize().testTag(UiTags.screen(view))) {
                when (view) {
                    SessionView.Terminal -> TerminalView(state)
                    SessionView.Sftp -> SftpView()
                    SessionView.Monitor -> MonitorView(state)
                    SessionView.Runbook -> RunbookRunView(state)
                    // A remote desktop never renders here (see activeTerminal); keep the branch total.
                    SessionView.Vnc -> TerminalView(state)
                    SessionView.Player -> CastPlayerView()
                }
            }
        }
    }
}
