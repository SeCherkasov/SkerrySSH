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
        require(ansi.size == 16) { "ANSI-палитра терминала должна содержать ровно 16 цветов, было ${ansi.size}" }
    }

    /** Glyph color under a block cursor (contrast against [cursor]). */
    val cursorText: Color get() = background
}

/** Built-in terminal theme catalog. Order matches the Appearance card order. */
object TerminalThemes {

    /** Skerry default — "night sea". */
    val NightSea = TerminalTheme(
        id = "night-sea",
        displayName = "Night Sea",
        background = Color(0xFF050E16),
        foreground = Color(0xFFE6ECEF),
        cursor = Color(0xFF2BBDEE),
        ansi = listOf(
            Color(0xFF2A3540), Color(0xFFE94B4B), Color(0xFF5DCE9E), Color(0xFFF2A65A),
            Color(0xFF4A9EDB), Color(0xFFC792EA), Color(0xFF2BBDEE), Color(0xFFC9D6DE),
            Color(0xFF5A7080), Color(0xFFFF6B6B), Color(0xFF7FE9B8), Color(0xFFFFC078),
            Color(0xFF6FC3F5), Color(0xFFE0A8FF), Color(0xFF5FD1F4), Color(0xFFFFFFFF),
        ),
    )

    /** Tokyo Night — dark blue palette. */
    val TokyoNight = TerminalTheme(
        id = "tokyo-night",
        displayName = "Tokyo Night",
        background = Color(0xFF1A1B26),
        foreground = Color(0xFFC0CAF5),
        cursor = Color(0xFF7AA2F7),
        ansi = listOf(
            Color(0xFF15161E), Color(0xFFF7768E), Color(0xFF9ECE6A), Color(0xFFE0AF68),
            Color(0xFF7AA2F7), Color(0xFFBB9AF7), Color(0xFF7DCFFF), Color(0xFFA9B1D6),
            Color(0xFF414868), Color(0xFFF7768E), Color(0xFF9ECE6A), Color(0xFFE0AF68),
            Color(0xFF7AA2F7), Color(0xFFBB9AF7), Color(0xFF7DCFFF), Color(0xFFC0CAF5),
        ),
    )

    /** Gruvbox Dark — warm retro palette. */
    val GruvboxDark = TerminalTheme(
        id = "gruvbox-dark",
        displayName = "Gruvbox Dark",
        background = Color(0xFF282828),
        foreground = Color(0xFFEBDBB2),
        cursor = Color(0xFFFE8019),
        ansi = listOf(
            Color(0xFF282828), Color(0xFFCC241D), Color(0xFF98971A), Color(0xFFD79921),
            Color(0xFF458588), Color(0xFFB16286), Color(0xFF689D6A), Color(0xFFA89984),
            Color(0xFF928374), Color(0xFFFB4934), Color(0xFFB8BB26), Color(0xFFFABD2F),
            Color(0xFF83A598), Color(0xFFD3869B), Color(0xFF8EC07C), Color(0xFFEBDBB2),
        ),
    )

    /**
     * Solarized Light. Uses dark base01 (#586E75) for body text instead of the canonical pale
     * base00, since the pale value loses contrast on the cream background; selection is base2.
     */
    val SolarizedLight = TerminalTheme(
        id = "solarized-light",
        displayName = "Solarized Light",
        background = Color(0xFFFDF6E3),
        foreground = Color(0xFF586E75),
        cursor = Color(0xFF268BD2),
        ansi = listOf(
            Color(0xFF073642), Color(0xFFDC322F), Color(0xFF859900), Color(0xFFB58900),
            Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198), Color(0xFFEEE8D5),
            Color(0xFF002B36), Color(0xFFCB4B16), Color(0xFF586E75), Color(0xFF657B83),
            Color(0xFF839496), Color(0xFF6C71C4), Color(0xFF93A1A1), Color(0xFFFDF6E3),
        ),
        selection = Color(0xFFEEE8D5),
    )

    /** Catppuccin Mocha — soft dark-purple pastel (Base #1E1E2E / Text #CDD6F4). */
    val CatppuccinMocha = TerminalTheme(
        id = "catppuccin-mocha",
        displayName = "Catppuccin Mocha",
        background = Color(0xFF1E1E2E),
        foreground = Color(0xFFCDD6F4),
        cursor = Color(0xFFF5E0DC),
        ansi = listOf(
            Color(0xFF45475A), Color(0xFFF38BA8), Color(0xFFA6E3A1), Color(0xFFF9E2AF),
            Color(0xFF89B4FA), Color(0xFFF5C2E7), Color(0xFF94E2D5), Color(0xFFBAC2DE),
            Color(0xFF585B70), Color(0xFFF38BA8), Color(0xFFA6E3A1), Color(0xFFF9E2AF),
            Color(0xFF89B4FA), Color(0xFFF5C2E7), Color(0xFF94E2D5), Color(0xFFA6ADC8),
        ),
    )

    /** Dracula — dark palette with bright neon accents (#282A36 / #F8F8F2). */
    val Dracula = TerminalTheme(
        id = "dracula",
        displayName = "Dracula",
        background = Color(0xFF282A36),
        foreground = Color(0xFFF8F8F2),
        cursor = Color(0xFFF8F8F2),
        ansi = listOf(
            Color(0xFF21222C), Color(0xFFFF5555), Color(0xFF50FA7B), Color(0xFFF1FA8C),
            Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD), Color(0xFFF8F8F2),
            Color(0xFF6272A4), Color(0xFFFF6E6E), Color(0xFF69FF94), Color(0xFFFFFFA5),
            Color(0xFFD6ACFF), Color(0xFFFF92DF), Color(0xFFA4FFFF), Color(0xFFFFFFFF),
        ),
    )

    /**
     * Tokyo Day — light variant of Tokyo Night (folke tokyonight "day"). Selection is set to an
     * explicit light blue rather than a translucent accent, which would wash out the pale text.
     */
    val TokyoDay = TerminalTheme(
        id = "tokyo-day",
        displayName = "Tokyo Day",
        background = Color(0xFFE1E2E7),
        foreground = Color(0xFF3760BF),
        cursor = Color(0xFF3760BF),
        ansi = listOf(
            Color(0xFFB4B5B9), Color(0xFFF52A65), Color(0xFF587539), Color(0xFF8C6C3E),
            Color(0xFF2E7DE9), Color(0xFF9854F1), Color(0xFF007197), Color(0xFF6172B0),
            Color(0xFFA1A6C5), Color(0xFFF52A65), Color(0xFF587539), Color(0xFF8C6C3E),
            Color(0xFF2E7DE9), Color(0xFF9854F1), Color(0xFF007197), Color(0xFF3760BF),
        ),
        selection = Color(0xFFB7C1E3),
    )

    /** All themes in Appearance card order. */
    val all: List<TerminalTheme> = listOf(
        NightSea, TokyoNight, TokyoDay, CatppuccinMocha, GruvboxDark, Dracula, SolarizedLight,
    )

    val DEFAULT: TerminalTheme = NightSea

    /** Theme by stable [TerminalTheme.id]; unknown/`null`/empty id falls back to [DEFAULT]. */
    fun fromId(id: String?): TerminalTheme = all.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * Active terminal theme. Defaults to [TerminalThemes.DEFAULT] where no provider is set (mobile,
 * preview, connection screen). Set by [app.skerry.ui.desktop.DesktopDesignApp].
 */
val LocalTerminalTheme = staticCompositionLocalOf { TerminalThemes.DEFAULT }
