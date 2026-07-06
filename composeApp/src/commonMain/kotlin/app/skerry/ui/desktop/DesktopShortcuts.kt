package app.skerry.ui.desktop

import androidx.compose.ui.input.key.Key

/**
 * A global hotkey of the desktop shell (titlebar/rail/sessions), recognized from a key chord.
 * A pure type with no Compose dependency — matching ([matchDesktopShortcut]) and execution
 * ([runDesktopShortcut]) are tested without composition.
 */
sealed interface DesktopShortcut {
    /** Open the "New connection" modal (⌘N / Ctrl+Shift+N). */
    data object NewConnection : DesktopShortcut

    /** Split/collapse the active tab's terminal pane (⌘D / Ctrl+Shift+D). */
    data object SplitTerminal : DesktopShortcut

    /** Open the active tab's SFTP (⌘F / Ctrl+Shift+F). */
    data object OpenSftp : DesktopShortcut

    /** Focus the AI bar's input field (⌘/ / Ctrl+Shift+/). */
    data object FocusAiBar : DesktopShortcut

    /** Lock the vault (⌘L / Ctrl+Shift+L). */
    data object Lock : DesktopShortcut

    /** Next tab (Ctrl+Tab). */
    data object NextTab : DesktopShortcut

    /** Previous tab (Ctrl+Shift+Tab). */
    data object PrevTab : DesktopShortcut

    /** Select a tab by number, 0-based (Alt+1..9). */
    data class SelectTab(val index: Int) : DesktopShortcut
}

/**
 * Matches a key chord to a global shell hotkey, or `null` if there's no match.
 *
 * The scheme is chosen to NOT steal keys from the terminal (the root `onPreviewKeyEvent` sees
 * them before it does):
 * - **Alt+digit** — select tab. A user-requested requirement; deliberately intercepts
 *   Alt+digit (the terminal's Meta prefix `ESC 1`) in favor of navigation.
 * - **Ctrl+Tab / Ctrl+Shift+Tab** — tab switching (Tab isn't a letter, so it doesn't collide
 *   with the terminal's `Ctrl+letter`→C0 mapping).
 * - **App modifier** = `⌘` (Meta without Ctrl/Alt) on macOS OR `Ctrl+Shift` on Linux/Windows.
 *   Requiring Shift on the Ctrl path leaves plain `Ctrl+letter` to the terminal (Ctrl+L clear,
 *   Ctrl+D EOF, Ctrl+C signal etc. keep working in the shell), and `Ctrl+Shift+C/V` is untouched —
 *   that's copy/paste.
 *
 * AltGr (on many layouts = Ctrl+Alt) is excluded from the Alt+digit branch via the `!ctrl` check.
 */
fun matchDesktopShortcut(ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean, key: Key): DesktopShortcut? {
    // Alt+digit → select tab (Alt only, no other modifiers).
    if (alt && !ctrl && !meta && !shift) {
        digitIndex(key)?.let { return DesktopShortcut.SelectTab(it) }
    }
    // Cyclic tab switching.
    if (ctrl && !alt && !meta && key == Key.Tab) {
        return if (shift) DesktopShortcut.PrevTab else DesktopShortcut.NextTab
    }
    val appMod = (meta && !ctrl && !alt) || (ctrl && shift && !alt && !meta)
    if (!appMod) return null
    return when (key) {
        Key.N -> DesktopShortcut.NewConnection
        Key.D -> DesktopShortcut.SplitTerminal
        Key.F -> DesktopShortcut.OpenSftp
        Key.L -> DesktopShortcut.Lock
        Key.Slash -> DesktopShortcut.FocusAiBar
        else -> null
    }
}

/** Tab index (0-based) for the top-row digit key 1..9, else `null`. */
private fun digitIndex(key: Key): Int? = when (key) {
    Key.One -> 0
    Key.Two -> 1
    Key.Three -> 2
    Key.Four -> 3
    Key.Five -> 4
    Key.Six -> 5
    Key.Seven -> 6
    Key.Eight -> 7
    Key.Nine -> 8
    else -> null
}
