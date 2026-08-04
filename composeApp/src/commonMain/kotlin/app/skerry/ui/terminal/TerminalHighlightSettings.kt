package app.skerry.ui.terminal

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.terminal.highlight.HighlightKind

/**
 * Which client-side highlighting the terminal applies. Two switches rather than one because the two
 * halves differ in whose text they touch: [commandLine] colors what the user types (on by default),
 * while [output] repaints what the server printed — an opinion about someone else's output, so it
 * stays off until asked for.
 */
@Immutable
data class TerminalHighlight(
    val commandLine: Boolean = true,
    val output: Boolean = false,
) {
    val enabled: Boolean get() = commandLine || output
}

/**
 * Highlighting settings for the terminal subtree. A composition local (like
 * [LocalTerminalAppearance]) so the setting reaches every terminal surface — panes, the mobile view,
 * the recording player — without threading two more parameters through their signatures.
 */
val LocalTerminalHighlight = staticCompositionLocalOf { TerminalHighlight() }

/** Number of highlight categories — the width of the per-style glyph cache in the render layer. */
internal val HIGHLIGHT_KIND_COUNT = HighlightKind.entries.size

/** Longest a session's executed-command set may grow; mirrors the cap on command history. */
internal const val MAX_EXECUTED_COMMANDS = 500
