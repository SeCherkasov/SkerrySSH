package app.skerry.ui.settings

/**
 * Whether the current platform is macOS. Determines shortcut-label symbols on the Keyboard page: on
 * Apple the app modifier is `⌘`/`⌥`, on Linux/Windows it's `Ctrl+Shift`/`Alt` (matches
 * [matchDesktopShortcut]). The Android actual returns `false` (the desktop page isn't shown there).
 */
internal expect fun isApplePlatform(): Boolean
