package app.skerry.ui.terminal

/**
 * Text from the PRIMARY selection (X11/Wayland): middle-click pastes this by convention — whatever is
 * mouse-selected in any window — not the system CLIPBOARD. Platforms without primary selection
 * (Android, Windows, macOS) return `null`; the caller falls back to the regular clipboard.
 */
internal expect fun readPrimarySelectionText(): String?

/**
 * Writes text to the PRIMARY selection (X11): mouse selection in the terminal becomes PRIMARY
 * immediately, so middle-click in this or other windows pastes it. No-op on platforms without PRIMARY
 * (Wayland/Android/Windows/macOS); the caller keeps its own in-app fallback buffer.
 */
internal expect fun writePrimarySelectionText(text: String)
