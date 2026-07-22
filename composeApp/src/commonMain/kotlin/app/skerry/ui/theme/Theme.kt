package app.skerry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * The "night sea" palette — the app's original dark theme. This is the default and the
 * reference for token values.
 */
fun nightSeaColors(): SkerryColors {
    val cyan = Color(0xFF2BBDEE)
    return SkerryColors(
        // Base backgrounds and surfaces
        bg = Color(0xFF07141E),
        railBg = Color(0xFF0A1620),
        titleTop = Color(0xFF0C1A24),
        titleBottom = Color(0xFF0A1620),
        surface = Color(0xFF0A141B),
        surface2 = Color(0xFF0B1A26),
        surfaceDeep = Color(0xFF0E2230),
        panel = Color(0xFF08121C),
        terminalBg = Color(0xFF050E16),
        // Text
        text = Color(0xFFE6ECEF),
        textBright = Color(0xFFC9D6DE),
        textMid = Color(0xFFB7C5CC),
        dim = Color(0xFF8FA3B0),
        faint = Color(0xFF5A7080),
        // Accents
        cyan = cyan,
        cyanBright = Color(0xFF5FD1F4),
        moss = Color(0xFF5DCE9E),
        sunset = Color(0xFFE07A5F),
        amber = Color(0xFFF2A65A),
        amberBright = Color(0xFFFFC078),
        // Teal logo accent
        teal = Color(0xFF34D3C0),
        tealLight = Color(0xFF7FF0E2),
        tealDeep = Color(0xFF22B3A4),
        // Fixed tones
        white = Color(0xFFFFFFFF),
        storm = Color(0xFFE94B4B),
        // Borders — cyan tint on the dark surfaces
        line = cyan.copy(alpha = 0.06f),
        lineStrong = cyan.copy(alpha = 0.14f),
        // Shared surfaces / accents
        strictFg = Color(0xFFE07060),
        whiteFaint = Color(0x1AFFFFFF), // rgba(255,255,255,0.1) — disabled toggle background
        ink = Color(0xFF0A1A26),        // text/icon color on cyan-accented buttons
        card = Color(0x08FFFFFF),       // row card background (rgba(255,255,255,0.03))
        scrim = Color(0xB304080C),      // dimming under mobile sheets/dialogs
        modalScrim = Color(0xB3060E16), // dimming under desktop modals
    )
}

/** Maps app-chrome tokens onto the Material3 [ColorScheme] used by the few Material components. */
fun SkerryColors.toMaterialColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = cyan,
        onPrimary = bg,
        primaryContainer = surfaceDeep,
        onPrimaryContainer = cyanBright,
        secondary = cyanBright,
        onSecondary = bg,
        tertiary = amber,
        onTertiary = bg,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surfaceDeep,
        onSurfaceVariant = dim,
        surfaceContainer = ink,
        surfaceContainerHigh = surfaceDeep,
        surfaceContainerLow = surface2,
        error = storm,
        onError = bg,
        outline = lineStrong,
        outlineVariant = line,
    )
}

/**
 * The "daybreak" light palette — a cool, high-key counterpart to night sea. Backgrounds are a cool
 * off-white with white cards stratified above them; accents are darkened for AA contrast on light
 * surfaces; borders are neutral ink tints rather than the cyan tint used on dark.
 */
fun daybreakColors(): SkerryColors {
    val ink = 0xFF0D1E29 // the darkest text/border base, reused for translucent lines
    val cyan = Color(0xFF0E90BF)
    return SkerryColors(
        // Base backgrounds and surfaces
        bg = Color(0xFFF3F6F8),
        railBg = Color(0xFFEBF0F3),
        titleTop = Color(0xFFFFFFFF),
        titleBottom = Color(0xFFEDF2F5),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFEEF3F6),
        surfaceDeep = Color(0xFFE1E9EE),
        panel = Color(0xFFF3F6F8),
        terminalBg = Color(0xFFEDF1F4),
        // Text — inverted lightness ladder: darkest is the primary text
        text = Color(ink),
        textBright = Color(0xFF243B49),
        textMid = Color(0xFF3B5361),
        dim = Color(0xFF5E7583),
        faint = Color(0xFF93A6B2),
        // Accents — darkened for contrast on light surfaces
        cyan = cyan,
        cyanBright = Color(0xFF14A6DB),
        moss = Color(0xFF2C9E71),
        sunset = Color(0xFFCE5B3E),
        amber = Color(0xFFB9761B),
        amberBright = Color(0xFFD98E2C),
        // Teal logo accent — kept vivid for brand recognition
        teal = Color(0xFF12A897),
        tealLight = Color(0xFF4FD1C2),
        tealDeep = Color(0xFF0B7F73),
        // Fixed tones
        white = Color(0xFFFFFFFF),
        storm = Color(0xFFCE3B3B),
        // Borders — neutral ink tint, not cyan
        line = Color(ink).copy(alpha = 0.07f),
        lineStrong = Color(ink).copy(alpha = 0.18f),
        // Shared surfaces / accents
        strictFg = Color(0xFFB4543E),
        whiteFaint = Color(ink).copy(alpha = 0.08f), // disabled toggle background (dark tint on light)
        ink = Color(0xFF08303F),                     // text/icon color on cyan-accented buttons
        card = Color(0x0D0D1E29),                    // row card — a faint ink darkening on light (mirrors the white lift on dark)
        scrim = Color(0x66101922),                   // dimming under mobile sheets/dialogs
        modalScrim = Color(0x73101922),              // dimming under desktop modals
    )
}

/** User-facing theme choice, persisted by id. [SYSTEM] follows the OS; the default is [DARK]. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        val DEFAULT = DARK
        fun fromId(id: String): ThemeMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Resolves a [ThemeMode] to its palette and dark flag, consulting the OS for [ThemeMode.SYSTEM]. */
@Composable
fun ThemeMode.resolveColors(): Pair<SkerryColors, Boolean> {
    val dark = when (this) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    return (if (dark) nightSeaColors() else daybreakColors()) to dark
}

/**
 * Wraps the UI in the app theme for the chosen [mode]: provides the active [SkerryColors] to the
 * tree and mirrors it onto Material3. Defaults to [ThemeMode.DEFAULT] (night-sea dark) so call sites
 * that don't pick a theme keep the original appearance.
 */
@Composable
fun SkerryTheme(
    mode: ThemeMode = ThemeMode.DEFAULT,
    content: @Composable () -> Unit,
) {
    val (colors, dark) = mode.resolveColors()
    CompositionLocalProvider(LocalSkerryColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(dark),
            content = content,
        )
    }
}
