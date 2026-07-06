package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.DEFAULT_MAX_SCROLLBACK
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.hack_bold
import app.skerry.ui.generated.resources.hack_regular
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import kotlin.math.round
import org.jetbrains.compose.resources.Font

/**
 * Terminal font, chosen in settings (Appearance → Font). Both variants render WITHOUT programmatic
 * ligatures ([NO_LIGATURES]): `->`, `=>`, `!=` stay separate glyphs — required for the terminal's
 * per-character grid. Hack has no ligatures by design; JetBrains Mono does, so disabling them matters
 * there. [id] is a stable key for persistence (see desktop `main`).
 */
enum class TerminalFont(val displayName: String, val id: String) {
    /** Hack — monospace without ligatures, terminal default. */
    Hack("Hack", "hack"),

    /** JetBrains Mono — Skerry's design-token font (same as the rest of the UI); ligatures forced off. */
    JetBrainsMono("JetBrains Mono", "jetbrains-mono");

    companion object {
        val DEFAULT = Hack

        /** Parses a persisted [id] back into a value; unknown/`null` → [DEFAULT]. */
        fun fromId(id: String?): TerminalFont = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Default terminal font size. Applied as `.sp` (see [TerminalAppearance.fontSizeSp]); the slider
 * labels it "px" — at font scale 1.0 these are the same number.
 */
const val DEFAULT_TERMINAL_FONT_SIZE = 13

/** Terminal font size bounds (px) for the Appearance → Font size slider. */
const val TERMINAL_FONT_SIZE_MIN = 8
const val TERMINAL_FONT_SIZE_MAX = 40
val TERMINAL_FONT_SIZE_RANGE: IntRange = TERMINAL_FONT_SIZE_MIN..TERMINAL_FONT_SIZE_MAX

/**
 * Default line-height-to-font-size ratio (13px → 18px). Line height = font size × this value, so
 * line spacing scales with font size. Adjustable via the Appearance → Line height slider within
 * [TERMINAL_LINE_HEIGHT_MIN]..[TERMINAL_LINE_HEIGHT_MAX].
 */
const val DEFAULT_TERMINAL_LINE_HEIGHT = 18f / 13f
const val TERMINAL_LINE_HEIGHT_MIN = 1.0f
const val TERMINAL_LINE_HEIGHT_MAX = 2.0f

/**
 * Terminal letter spacing (sp/px at scale 1.0), Appearance → Letter spacing slider. Defaults to 0
 * (natural font advance); negative compresses, positive expands. The terminal grid stays consistent
 * since cell width is measured with the same [TextStyle] and accounts for spacing.
 */
const val DEFAULT_TERMINAL_LETTER_SPACING = 0f
const val TERMINAL_LETTER_SPACING_MIN = -1.5f
const val TERMINAL_LETTER_SPACING_MAX = 4.0f

/** Rounds a float to 2 decimals, so the slider doesn't produce "1.4000001" in state/persistence. */
private fun round2(value: Float): Float = round(value * 100f) / 100f

/** Clamps line height to the valid range and rounds it. */
fun clampTerminalLineHeight(value: Float): Float =
    round2(value.coerceIn(TERMINAL_LINE_HEIGHT_MIN, TERMINAL_LINE_HEIGHT_MAX))

/** Clamps letter spacing to the valid range and rounds it. */
fun clampTerminalLetterSpacing(value: Float): Float =
    round2(value.coerceIn(TERMINAL_LETTER_SPACING_MIN, TERMINAL_LETTER_SPACING_MAX))

/**
 * Disables programmatic ligatures via `TextStyle.fontFeatureSettings`: turns off `liga`/`calt`/`dlig`
 * so pairs like `->` don't merge into one glyph. Applied to the terminal font regardless of choice
 * (harmless for Hack, necessary for JetBrains Mono).
 */
const val NO_LIGATURES = "\"liga\" 0, \"calt\" 0, \"dlig\" 0"

/**
 * Terminal cursor style (Settings → Terminal → cursor style): shape (DECSCUSR block/underline/bar)
 * combined with blink. Acts as the DEFAULT for a new session — the app inside can override the shape
 * via DECSCUSR (`CSI Ps SP q`); RIS resets back to the chosen style. [id] is a stable persistence key
 * (see desktop `main`); [entries] order matches the dropdown order.
 */
enum class TerminalCursorStyle(val shape: CursorShape, val blink: Boolean, val id: String) {
    BlockBlink(CursorShape.Block, true, "block-blink"),
    BlockSteady(CursorShape.Block, false, "block-steady"),
    UnderlineBlink(CursorShape.Underline, true, "underline-blink"),
    UnderlineSteady(CursorShape.Underline, false, "underline-steady"),
    BarBlink(CursorShape.Bar, true, "bar-blink"),
    BarSteady(CursorShape.Bar, false, "bar-steady");

    companion object {
        /** Default — blinking block (matches xterm). */
        val DEFAULT = BlockBlink

        /** Parses a persisted [id] back into a value; unknown/`null` → [DEFAULT]. */
        fun fromId(id: String?): TerminalCursorStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Default scrollback depth (Settings → Terminal → scrollback buffer), in rows. */
const val DEFAULT_TERMINAL_SCROLLBACK = 10_000

/** Scrollback depth presets (rows) for the scrollback-buffer dropdown. */
val TERMINAL_SCROLLBACK_OPTIONS: List<Int> = listOf(1_000, 5_000, 10_000, 50_000)

/**
 * Terminal settings applied to a NEW session on creation ([app.skerry.ui.connection.ConnectionController]):
 * default scrollback depth and cursor style. Read by the provider at connect time, so changing
 * settings affects subsequent sessions; already-open sessions keep their own emulator.
 */
@Immutable
data class TerminalSessionPrefs(
    val scrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
) {
    /** Effective scrollback depth: validated preset, else the emulator default (safety net). */
    val effectiveScrollback: Int get() = scrollback.takeIf { it > 0 } ?: DEFAULT_MAX_SCROLLBACK
}

/** Terminal appearance, threaded into [TerminalScreen] via [LocalTerminalAppearance]. */
@Immutable
data class TerminalAppearance(
    val font: TerminalFont = TerminalFont.DEFAULT,
    val fontSizeSp: Int = DEFAULT_TERMINAL_FONT_SIZE,
    /** Line height multiplier (see [DEFAULT_TERMINAL_LINE_HEIGHT]): lineHeight = font size × this value. */
    val lineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    /** Letter spacing in sp (see [DEFAULT_TERMINAL_LETTER_SPACING]). */
    val letterSpacingSp: Float = DEFAULT_TERMINAL_LETTER_SPACING,
)

/**
 * Terminal appearance for the whole tree. The default ([TerminalAppearance] default = Hack 13px)
 * applies wherever no provider is set (mobile target, preview, connection screen). The desktop root
 * ([app.skerry.ui.desktop.DesktopDesignApp]) overrides it with the user's settings choice.
 */
val LocalTerminalAppearance = staticCompositionLocalOf { TerminalAppearance() }

/**
 * Terminal font family for the chosen [font] (regular + bold from compose-resources). No `remember`
 * needed internally: `Font(...)` is itself @Composable and caches resource data in the composition
 * slot (like [app.skerry.ui.design.rememberMono] and siblings). The `remember` prefix is kept for
 * naming consistency with the project's font factories.
 */
@Composable
fun rememberTerminalFontFamily(font: TerminalFont): FontFamily = when (font) {
    TerminalFont.Hack -> FontFamily(
        Font(Res.font.hack_regular, weight = FontWeight.Normal),
        Font(Res.font.hack_bold, weight = FontWeight.Bold),
    )
    TerminalFont.JetBrainsMono -> FontFamily(
        Font(Res.font.jetbrainsmono_regular, weight = FontWeight.Normal),
        Font(Res.font.jetbrainsmono_bold, weight = FontWeight.Bold),
    )
}
