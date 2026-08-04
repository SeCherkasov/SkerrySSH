package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import app.skerry.ui.session.SessionView
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
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
    when (state.appOverlay) {
        DesktopView.Ports -> TunnelsView()
        DesktopView.Snippets -> SnippetsView(state)
        DesktopView.Runbooks -> RunbooksView(state)
        DesktopView.Vault -> VaultView()
        DesktopView.Known -> KnownHostsView()
        DesktopView.Teams -> TeamsView()
        // overlay == null: renders the work area (showView only stores app-level values in appOverlay).
        else -> {
            val sessions = LocalSessions.current
            when (workAreaSection(sessions?.active, state.section)) {
                HostSection.RemoteDesktops -> RemoteDesktopsView(state)
                HostSection.Terminal -> {
                    // activeTerminal, not active: a remote-desktop tab renders in the branch above.
                    val view = sessions?.activeTerminal?.view ?: state.view.asSessionView()
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
}
