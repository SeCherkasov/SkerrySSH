package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import app.skerry.ui.session.SessionView
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.host.HostSection
import app.skerry.ui.known.KnownHostsView
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.sftp.SftpView
import app.skerry.ui.snippet.SnippetsView
import app.skerry.ui.teams.TeamsView
import app.skerry.ui.terminal.CastPlayerView
import app.skerry.ui.terminal.TerminalView
import app.skerry.ui.tunnel.TunnelsView
import app.skerry.ui.vault.VaultView
import app.skerry.ui.vnc.RemoteDesktopsView
import app.skerry.ui.app.asSessionView

/**
 * Switches the main content area. App-level views (Vault/Known/Teams/Snippets) render over
 * everything per [DesktopDesignState.appOverlay]; otherwise the selected work-area section
 * ([DesktopDesignState.section]) decides: remote desktops render their own catalog + framebuffer,
 * the terminal section renders the active terminal tab's subview
 * ([app.skerry.ui.session.Session.view]), falling back to [state.view] with no live sessions.
 */
@Composable
fun Viewport(state: DesktopDesignState) {
    when (state.appOverlay) {
        DesktopView.Ports -> TunnelsView()
        DesktopView.Snippets -> SnippetsView(state)
        DesktopView.Vault -> VaultView()
        DesktopView.Known -> KnownHostsView()
        DesktopView.Teams -> TeamsView()
        // overlay == null: renders the selected section (showView only stores app-level values in appOverlay).
        else -> when (state.section) {
            HostSection.RemoteDesktops -> RemoteDesktopsView(state)
            HostSection.Terminal -> {
                val sessions = LocalSessions.current
                // activeTerminal, not active: a remote-desktop tab may still be the selected one
                // (its section has no tab to fall back to), and it is not this section's to render.
                val view = sessions?.activeTerminal?.view ?: state.view.asSessionView()
                when (view) {
                    SessionView.Terminal -> TerminalView(state)
                    SessionView.Sftp -> SftpView()
                    // A remote desktop never renders here (see activeTerminal); keep the branch total.
                    SessionView.Vnc -> TerminalView(state)
                    SessionView.Player -> CastPlayerView()
                }
            }
        }
    }
}
