package app.skerry.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// The night-sea (dark) and daybreak (light) palettes — nightSeaColors() / daybreakColors() — are
// generated from composeApp/themes/chrome-themes.xml by :composeApp:generateThemeSources. Edit the XML,
// not Kotlin, to change a token.

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

/** User-facing theme choice, persisted by id. [SYSTEM] follows the OS; the default is [DARK]. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    BLACKWATER("blackwater"),
    TOKYO_NIGHT("tokyo-night"),
    TOKYO_DAY("tokyo-day"),
    CATPPUCCIN_MOCHA("catppuccin-mocha"),
    GRUVBOX_DARK("gruvbox-dark"),
    DRACULA("dracula"),
    SOLARIZED_LIGHT("solarized-light");

    companion object {
        val DEFAULT = DARK
        fun fromId(id: String): ThemeMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Static palette of this mode; [systemDark] resolves [ThemeMode.SYSTEM] (to the default pair only —
 * named themes are an explicit choice). Non-composable, so preview cards can render every palette
 * without touching the OS watcher.
 */
fun ThemeMode.palette(systemDark: Boolean): SkerryColors = when (this) {
    ThemeMode.SYSTEM -> if (systemDark) nightSeaColors() else daybreakColors()
    ThemeMode.LIGHT -> daybreakColors()
    ThemeMode.DARK -> nightSeaColors()
    ThemeMode.BLACKWATER -> blackwaterColors()
    ThemeMode.TOKYO_NIGHT -> tokyoNightColors()
    ThemeMode.TOKYO_DAY -> tokyoDayColors()
    ThemeMode.CATPPUCCIN_MOCHA -> catppuccinMochaColors()
    ThemeMode.GRUVBOX_DARK -> gruvboxDarkColors()
    ThemeMode.DRACULA -> draculaColors()
    ThemeMode.SOLARIZED_LIGHT -> solarizedLightColors()
}

/** Whether this mode renders dark; [systemDark] resolves [ThemeMode.SYSTEM]. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT, ThemeMode.TOKYO_DAY, ThemeMode.SOLARIZED_LIGHT -> false
    ThemeMode.SYSTEM -> systemDark
    else -> true
}

/**
 * Stable id of this app theme's terminal twin (unified theming): catalog modes share ids with the
 * terminal catalog, the stock pair maps to Night Sea / Daybreak, [ThemeMode.SYSTEM] follows the OS
 * side. Resolved through `TerminalThemes.fromId` by the consumer.
 */
fun ThemeMode.terminalThemeId(systemDark: Boolean): String = when (this) {
    ThemeMode.SYSTEM -> if (systemDark) "night-sea" else "daybreak"
    ThemeMode.DARK -> "night-sea"
    ThemeMode.LIGHT -> "daybreak"
    else -> id
}

/** Resolves a [ThemeMode] to its palette and dark flag, consulting the OS for [ThemeMode.SYSTEM]. */
@Composable
fun ThemeMode.resolveColors(): Pair<SkerryColors, Boolean> {
    // Composed unconditionally so the slot structure is identical for every mode: a composable
    // call living inside one `when` branch shifts sibling slots when the mode changes (stale-slot
    // ClassCastException under hot reload). The OS watcher itself only runs for SYSTEM.
    val systemDark = systemInDarkTheme(enabled = this == ThemeMode.SYSTEM)
    return palette(systemDark) to isDark(systemDark)
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
    val (target, dark) = mode.resolveColors()
    // Cross-fade every token so a theme switch (including a live OS light↔dark flip under
    // ThemeMode.SYSTEM) glides instead of snapping. First composition snaps to the target.
    val colors = animateSkerryColors(target)
    CompositionLocalProvider(LocalSkerryColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(dark),
            content = content,
        )
    }
}

private val ThemeTransitionSpec = tween<Color>(durationMillis = 250)

/** Returns [target] with every base token animated, so a palette change transitions smoothly. */
@Composable
private fun animateSkerryColors(target: SkerryColors): SkerryColors {
    @Composable fun anim(value: Color): Color = animateColorAsState(value, ThemeTransitionSpec).value
    return SkerryColors(
        bg = anim(target.bg),
        railBg = anim(target.railBg),
        titleTop = anim(target.titleTop),
        titleBottom = anim(target.titleBottom),
        surface = anim(target.surface),
        surface2 = anim(target.surface2),
        surfaceDeep = anim(target.surfaceDeep),
        panel = anim(target.panel),
        terminalBg = anim(target.terminalBg),
        text = anim(target.text),
        textBright = anim(target.textBright),
        textMid = anim(target.textMid),
        dim = anim(target.dim),
        faint = anim(target.faint),
        cyan = anim(target.cyan),
        cyanBright = anim(target.cyanBright),
        moss = anim(target.moss),
        sunset = anim(target.sunset),
        amber = anim(target.amber),
        amberBright = anim(target.amberBright),
        teal = anim(target.teal),
        tealLight = anim(target.tealLight),
        tealDeep = anim(target.tealDeep),
        white = anim(target.white),
        storm = anim(target.storm),
        line = anim(target.line),
        lineStrong = anim(target.lineStrong),
        strictFg = anim(target.strictFg),
        whiteFaint = anim(target.whiteFaint),
        ink = anim(target.ink),
        card = anim(target.card),
        scrim = anim(target.scrim),
        modalScrim = anim(target.modalScrim),
        overlayFaint = anim(target.overlayFaint),
        overlaySoft = anim(target.overlaySoft),
        overlayMed = anim(target.overlayMed),
        overlayStrong = anim(target.overlayStrong),
        hover = anim(target.hover),
        sunsetInk = anim(target.sunsetInk),
        bannerScrim = anim(target.bannerScrim),
    )
}
