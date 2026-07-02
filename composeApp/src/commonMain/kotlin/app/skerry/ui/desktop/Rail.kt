package app.skerry.ui.desktop

import app.skerry.ui.app.DesktopView

/** Пункт левого icon-rail desktop-оболочки. */
data class RailItem(val view: DesktopView, val icon: String, val label: String)

// Files намеренно нет в рейле: SFTP открывается быстрой кнопкой (иконка folder) прямо на терминале
// активной сессии — отдельный пункт рейла дублировал бы её. [DesktopView.Sftp] остаётся session-вью.
val RAIL = listOf(
    RailItem(DesktopView.Terminal, "terminal", "Terminal"),
    RailItem(DesktopView.Ports, "lan", "Tunnels"),
    RailItem(DesktopView.Snippets, "code_blocks", "Snippets"),
    RailItem(DesktopView.Vault, "vpn_key", "Vault"),
    RailItem(DesktopView.Known, "fingerprint", "Hosts"),
    RailItem(DesktopView.Teams, "groups", "Team"),
)
