package app.skerry.ui.design

import androidx.compose.runtime.Composable
import app.skerry.ui.session.SessionView

/**
 * Переключатель основной области. App-level view (Vault/Known/Teams/Snippets) показываются поверх
 * вкладок по [DesktopDesignState.appOverlay]; иначе рисуем подвью активной вкладки
 * ([app.skerry.ui.session.Session.view]) — а без живых сессий (мок/превью) её фолбэк [state.view].
 */
@Composable
fun Viewport(state: DesktopDesignState) {
    when (val overlay = state.appOverlay) {
        DesktopView.Snippets -> SnippetsView()
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
                SessionView.Ports -> TunnelsView()
            }
        }
    }
}
