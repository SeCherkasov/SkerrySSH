package app.skerry.ui.terminal

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Terminal color theme (Appearance → theme picker): background, base text color, cursor accent,
 * and ANSI 0..15 palette. Read via [LocalTerminalTheme] so a theme switch recolors already-open
 * sessions live. Indices 16..255 (xterm cube + grayscale) are theme-independent, fixed by the standard.
 *
 * [cursorText] and [selection] derive from the accent for consistent contrast per theme: the glyph
 * under a block cursor matches the background, and selection highlight is the accent with alpha.
 */
@Immutable
data class TerminalTheme(
    val id: String,
    val displayName: String,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    /** ANSI palette: exactly 16 colors — 0..7 normal, 8..15 bright. */
    val ansi: List<Color>,
    /**
     * Selection highlight, drawn below glyphs (text stays on top, see [TerminalScreen]). Defaults
     * to the cursor accent with alpha; light themes override with an explicit light shade instead.
     */
    val selection: Color = cursor.copy(alpha = 0.3f),
) {
    init {
        require(ansi.size == 16) { "Terminal ANSI palette must contain exactly 16 colors, got ${ansi.size}" }
    }

    /** Glyph color under a block cursor (contrast against [cursor]). */
    val cursorText: Color get() = background
}

// The TerminalThemes catalog (NightSea, TokyoNight, … plus all/DEFAULT/fromId) is generated from
// composeApp/themes/terminal-themes.xml by :composeApp:generateThemeSources. Edit the XML, not Kotlin,
// to add a theme or change a color.

/**
 * Active terminal theme. Defaults to [TerminalThemes.DEFAULT] where no provider is set (mobile,
 * preview, connection screen). Set by [app.skerry.ui.desktop.DesktopDesignApp].
 */
val LocalTerminalTheme = staticCompositionLocalOf { TerminalThemes.DEFAULT }
