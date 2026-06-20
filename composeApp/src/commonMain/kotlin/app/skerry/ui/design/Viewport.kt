package app.skerry.ui.design

import androidx.compose.runtime.Composable

/** Переключатель основной области по [DesktopDesignState.view] — 7 view макета. */
@Composable
fun Viewport(state: DesktopDesignState) {
    when (state.view) {
        DesktopView.Terminal -> TerminalView(state)
        DesktopView.Sftp -> SftpView()
        DesktopView.Ports -> TunnelsView()
        DesktopView.Snippets -> SnippetsView()
        DesktopView.Vault -> VaultView()
        DesktopView.Known -> KnownHostsView()
        DesktopView.Teams -> TeamsView()
    }
}
