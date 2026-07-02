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
import app.skerry.ui.terminal.TerminalView
import app.skerry.ui.tunnel.TunnelsView
import app.skerry.ui.vault.VaultView
import app.skerry.ui.app.asSessionView

/**
 * Переключатель основной области. App-level view (Vault/Known/Teams/Snippets) показываются поверх
 * вкладок по [DesktopDesignState.appOverlay]; иначе рисуем подвью активной вкладки
 * ([app.skerry.ui.session.Session.view]) — а без живых сессий (мок/превью) её фолбэк [state.view].
 */
@Composable
fun Viewport(state: DesktopDesignState) {
    when (state.appOverlay) {
        DesktopView.Ports -> TunnelsView()
        DesktopView.Snippets -> SnippetsView(state)
        DesktopView.Vault -> VaultView()
        DesktopView.Known -> KnownHostsView()
        DesktopView.Teams -> TeamsView()
        // overlay == null: показываем подвью активной вкладки (showView кладёт в appOverlay только
        // app-level значения, поэтому session-level сюда не попадает).
        else -> {
            val sessions = LocalSessions.current
            val view = sessions?.active?.view ?: state.view.asSessionView()
            when (view) {
                SessionView.Terminal -> TerminalView(state)
                SessionView.Sftp -> SftpView()
            }
        }
    }
}
