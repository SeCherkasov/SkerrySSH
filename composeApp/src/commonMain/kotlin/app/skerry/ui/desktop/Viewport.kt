package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import app.skerry.ui.session.SessionView
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.known.KnownHostsView
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.sftp.SftpView
import app.skerry.ui.snippet.SnippetsView
import app.skerry.ui.teams.TeamsView
import app.skerry.ui.terminal.CastPlayerView
import app.skerry.ui.terminal.TerminalView
import app.skerry.ui.tunnel.TunnelsView
import app.skerry.ui.vault.VaultView
import app.skerry.ui.vnc.VncView
import app.skerry.ui.app.asSessionView

/**
 * Switches the main content area. App-level views (Vault/Known/Teams/Snippets) render over tabs
 * per [DesktopDesignState.appOverlay]; otherwise renders the active tab's subview
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
        // overlay == null: renders the active tab's subview (showView only stores app-level values in appOverlay).
        else -> {
            val sessions = LocalSessions.current
            val view = sessions?.active?.view ?: state.view.asSessionView()
            when (view) {
                SessionView.Terminal -> TerminalView(state)
                SessionView.Sftp -> SftpView()
                SessionView.Vnc -> VncView(state)
                SessionView.Player -> CastPlayerView()
            }
        }
    }
}
