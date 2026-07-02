package app.skerry.ui.settings

import app.skerry.ui.app.SettingsTab

/** Пункт навигации панели Settings. */
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
