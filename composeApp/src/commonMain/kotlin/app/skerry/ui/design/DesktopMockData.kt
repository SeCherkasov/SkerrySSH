package app.skerry.ui.design

import androidx.compose.ui.graphics.Color

/**
 * Статичные демонстрационные данные: rail, группы хостов, навигация настроек, AI-политики.
 * Живой каталог хостов/секреты подключаются отдельно; здесь — заглушки.
 */

data class RailItem(val view: DesktopView, val icon: String, val label: String)

val RAIL = listOf(
    RailItem(DesktopView.Terminal, "terminal", "Terminal"),
    RailItem(DesktopView.Sftp, "folder_open", "Files"),
    RailItem(DesktopView.Ports, "lan", "Tunnels"),
    RailItem(DesktopView.Snippets, "code_blocks", "Snippets"),
    RailItem(DesktopView.Vault, "vpn_key", "Vault"),
    RailItem(DesktopView.Known, "fingerprint", "Hosts"),
    RailItem(DesktopView.Teams, "groups", "Team"),
)

enum class HostStatus(val color: Color, val glow: Boolean) {
    On(D.moss, true), Off(D.faint, false), Warn(D.sunset, false),
}

data class MockHost(val name: String, val status: HostStatus, val badge: String? = null)
data class HostGroup(val name: String, val hosts: List<MockHost>)

val HOST_GROUPS = listOf(
    HostGroup(
        "Production",
        listOf(
            MockHost("prod-web-01", HostStatus.On, "STRICT"),
            MockHost("prod-web-02", HostStatus.On, "STRICT"),
            MockHost("db-master", HostStatus.On, "STRICT"),
        ),
    ),
    HostGroup(
        "Staging",
        listOf(
            MockHost("staging-web", HostStatus.Off),
            MockHost("staging-api", HostStatus.Off),
        ),
    ),
    HostGroup(
        "Homelab",
        listOf(
            MockHost("homelab-pi", HostStatus.On, "DEV"),
            MockHost("nas-truenas", HostStatus.Warn, "DEV"),
            MockHost("router-opnsense", HostStatus.Off),
            MockHost("k3s-control", HostStatus.Off),
        ),
    ),
)

data class SettingsNavItem(val tab: SettingsTab, val icon: String, val name: String)

val SETTINGS_NAV = listOf(
    SettingsNavItem(SettingsTab.Account, "person", "Account"),
    SettingsNavItem(SettingsTab.AI, "auto_awesome", "AI"),
    SettingsNavItem(SettingsTab.Sync, "sync", "Sync"),
    SettingsNavItem(SettingsTab.Security, "shield_lock", "Security"),
    SettingsNavItem(SettingsTab.Appearance, "palette", "Appearance"),
    SettingsNavItem(SettingsTab.Terminal, "terminal", "Terminal"),
    SettingsNavItem(SettingsTab.Keyboard, "keyboard", "Keyboard"),
    SettingsNavItem(SettingsTab.About, "info", "About"),
)

data class PolicyOption(val policy: AiPolicy, val icon: String, val title: String, val desc: String)

val POLICY_OPTIONS = listOf(
    PolicyOption(AiPolicy.Strict, "shield_lock", "Strict — production safety", "Local AI only. Every suggestion needs confirmation. Secrets sanitized before any prompt."),
    PolicyOption(AiPolicy.Balanced, "tune", "Balanced — cloud allowed", "Local AI by default. Cloud AI with explicit opt-in per request."),
    PolicyOption(AiPolicy.Permissive, "science", "Permissive — dev / homelab", "Any provider. Auto-suggestions without confirmation."),
    PolicyOption(AiPolicy.Off, "block", "Off — no AI", "Disable AI features for this connection."),
)
