package app.skerry.ui.theme

import androidx.compose.runtime.Composable

/**
 * Whether the OS is currently in dark mode, as a **reactive** read: when the system appearance
 * flips at runtime (e.g. GNOME light↔dark), this recomposes so [ThemeMode.SYSTEM] follows along.
 *
 * Android delegates to `isSystemInDarkTheme()` (already reactive via configuration changes). Desktop
 * needs its own OS listener — Compose Multiplatform resolves the desktop value once and never updates
 * it — so the desktop actual polls the platform color-scheme (XDG portal / registry / defaults).
 *
 * [enabled] gates the desktop OS watcher (subprocess/poll) so it only runs while the value is
 * actually consumed ([ThemeMode.SYSTEM]). The function itself must be composed UNCONDITIONALLY by
 * the caller — composing it inside only one `when` branch shifts sibling slots when the mode
 * changes and corrupts the composition under hot reload (ClassCastException from a stale slot).
 */
@Composable
expect fun systemInDarkTheme(enabled: Boolean): Boolean
