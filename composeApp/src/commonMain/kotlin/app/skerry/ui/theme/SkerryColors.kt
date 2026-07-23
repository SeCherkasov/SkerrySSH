package app.skerry.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The full set of app-chrome design tokens for one theme (light or dark).
 *
 * Field names mirror the historical `D` palette object so call sites read the same token by the
 * same name regardless of the active theme. A theme is chosen once for the whole app and provided
 * through [LocalSkerryColors]; read it in composables via `SkerryTheme.colors`.
 *
 * Base and semantic colors are constructor parameters so each theme sets them explicitly. The
 * alpha-tinted derivations (`cyan06`…`cyan20`, badge fills) are computed in the body so they track
 * whichever accent the theme supplies.
 */
@Immutable
data class SkerryColors(
    // Base backgrounds and surfaces
    val bg: Color,
    val railBg: Color,
    val titleTop: Color,
    val titleBottom: Color,
    val surface: Color,
    val surface2: Color,
    val surfaceDeep: Color,
    val panel: Color,
    val terminalBg: Color,
    // Text
    val text: Color,
    val textBright: Color,
    val textMid: Color,
    val dim: Color,
    val faint: Color,
    // Accents
    val cyan: Color,
    val cyanBright: Color,
    val moss: Color,
    val sunset: Color,
    val amber: Color,
    val amberBright: Color,
    // Teal logo accent
    val teal: Color,
    val tealLight: Color,
    val tealDeep: Color,
    // Fixed tones
    val white: Color,
    val storm: Color,
    // Borders — explicit so a light theme can use a neutral line instead of a cyan tint
    val line: Color,
    val lineStrong: Color,
    // Shared surfaces / accents that a light theme must invert rather than derive
    val strictFg: Color,
    val whiteFaint: Color,
    val ink: Color,
    val card: Color,
    val scrim: Color,
    val modalScrim: Color,
    // Raise fills — neutral "lift" tints (white on dark, ink on light), faintest to strongest
    val overlayFaint: Color,
    val overlaySoft: Color,
    val overlayMed: Color,
    val overlayStrong: Color,
    val hover: Color,
    // Ink on sunset (danger) buttons and the disconnect-banner backdrop
    val sunsetInk: Color,
    val bannerScrim: Color,
) {
    // Cyan-derived tones (line/background tints) — alpha over the theme's cyan
    val cyan06: Color get() = cyan.copy(alpha = 0.06f)
    val cyan08: Color get() = cyan.copy(alpha = 0.08f)
    val cyan10: Color get() = cyan.copy(alpha = 0.10f)
    val cyan14: Color get() = cyan.copy(alpha = 0.14f)
    val cyan20: Color get() = cyan.copy(alpha = 0.20f)
    val amberSoft: Color get() = amber.copy(alpha = 0.14f)

    // Badge fills
    val strictBg: Color get() = sunset.copy(alpha = 0.16f)
    val devBg: Color get() = moss.copy(alpha = 0.16f)

    // Darkening washes — theme-invariant: black-over darkens correctly on both palettes
    val shade15: Color get() = Color(0x26000000)
    val shade30: Color get() = Color(0x4D000000)
}

/**
 * The active app-chrome palette. Provided by [SkerryTheme]; there is no default so a missing
 * provider fails loudly rather than silently rendering an unthemed tree.
 */
val LocalSkerryColors = staticCompositionLocalOf<SkerryColors> {
    error("No SkerryColors provided — wrap the UI in SkerryTheme { }")
}

/**
 * Ambient accessor for the active [SkerryColors], mirroring `MaterialTheme.colorScheme`. Named
 * distinctly from the [SkerryTheme] wrapper composable so `Skerry.colors { }` call sites and the
 * `SkerryTheme { }` wrapper don't collide during name resolution.
 */
object Skerry {
    val colors: SkerryColors
        @Composable @ReadOnlyComposable
        get() = LocalSkerryColors.current
}
