package app.skerry.ui.theme

import androidx.compose.runtime.Composable

/**
 * Whether the OS is currently in dark mode, as a **reactive** read: when the system appearance
 * flips at runtime (e.g. GNOME light↔dark), this recomposes so [ThemeMode.SYSTEM] follows along.
 *
 * Android delegates to `isSystemInDarkTheme()` (already reactive via configuration changes). Desktop
 * needs its own OS listener — Compose Multiplatform resolves the desktop value once and never updates
 * it — so the desktop actual polls the platform color-scheme (XDG portal / registry / defaults).
 */
@Composable
expect fun systemInDarkTheme(): Boolean
