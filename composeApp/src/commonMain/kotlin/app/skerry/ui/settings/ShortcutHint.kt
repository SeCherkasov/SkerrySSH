package app.skerry.ui.settings

/**
 * Whether the current platform is macOS. Determines shortcut-label symbols on the Keyboard page: on
 * Apple the app modifier is `⌘`/`⌥`, on Linux/Windows it's `Ctrl+Shift`/`Alt` (matches
 * [matchDesktopShortcut]). The Android actual returns `false` (the desktop page isn't shown there).
 */
internal expect fun isApplePlatform(): Boolean

/**
 * How the keyboard-release chord is written for a reader: Settings → Keyboard and the remote
 * surface's accessible name say the same thing, so one can be searched for from the other. The
 * chord itself is [app.skerry.ui.vnc.isKeyboardRelease].
 */
internal fun releaseKeyboardChord(): String = if (isApplePlatform()) "⌃⌥⇧K" else "Ctrl+Alt+Shift+K"
