package app.skerry.ui.settings

import app.skerry.ui.app.SettingsTab

/** Пункт навигации панели Settings (подпись локализуется в [app.skerry.ui.settings]-navLabel). */
data class SettingsNavItem(val tab: SettingsTab, val icon: String)

val SETTINGS_NAV = listOf(
    SettingsNavItem(SettingsTab.Account, "person"),
    SettingsNavItem(SettingsTab.AI, "auto_awesome"),
    SettingsNavItem(SettingsTab.Sync, "sync"),
    SettingsNavItem(SettingsTab.Security, "shield_lock"),
    SettingsNavItem(SettingsTab.Appearance, "palette"),
    SettingsNavItem(SettingsTab.Terminal, "terminal"),
    SettingsNavItem(SettingsTab.Keyboard, "keyboard"),
    SettingsNavItem(SettingsTab.About, "info"),
)
