package app.skerry.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the night-sea palette to its reference token values.
 * These values are the source of truth; update this test first when they change.
 *
 * These values must not drift during the `D` → [SkerryColors] migration: the same token by the same
 * name must keep the same color, so the migration is a pure refactor with no visual change.
 */
class SkerryColorsTest {

    private val c = nightSeaColors()

    @Test
    fun `surface tokens match the reference values`() {
        assertEquals(Color(0xFF07141E), c.bg)
        assertEquals(Color(0xFF0A1620), c.railBg)
        assertEquals(Color(0xFF0A141B), c.surface)
        assertEquals(Color(0xFF0B1A26), c.surface2)
        assertEquals(Color(0xFF0E2230), c.surfaceDeep)
        assertEquals(Color(0xFF08121C), c.panel)
        assertEquals(Color(0xFF050E16), c.terminalBg)
    }

    @Test
    fun `text tokens match the reference values`() {
        assertEquals(Color(0xFFE6ECEF), c.text)
        assertEquals(Color(0xFFC9D6DE), c.textBright)
        assertEquals(Color(0xFFB7C5CC), c.textMid)
        assertEquals(Color(0xFF8FA3B0), c.dim)
        assertEquals(Color(0xFF5A7080), c.faint)
    }

    @Test
    fun `accent tokens match the reference values`() {
        assertEquals(Color(0xFF2BBDEE), c.cyan)
        assertEquals(Color(0xFF5FD1F4), c.cyanBright)
        assertEquals(Color(0xFF5DCE9E), c.moss)
        assertEquals(Color(0xFFE07A5F), c.sunset)
        assertEquals(Color(0xFFF2A65A), c.amber)
        assertEquals(Color(0xFFFFC078), c.amberBright)
        assertEquals(Color(0xFF34D3C0), c.teal)
        assertEquals(Color(0xFFE94B4B), c.storm)
    }

    @Test
    fun `translucent cyan tones are cyan with the documented alpha`() {
        assertEquals(c.cyan.copy(alpha = 0.06f), c.cyan06)
        assertEquals(c.cyan.copy(alpha = 0.08f), c.cyan08)
        assertEquals(c.cyan.copy(alpha = 0.10f), c.cyan10)
        assertEquals(c.cyan.copy(alpha = 0.14f), c.cyan14)
        assertEquals(c.cyan.copy(alpha = 0.20f), c.cyan20)
        assertEquals(c.cyan.copy(alpha = 0.06f), c.line)
        assertEquals(c.cyan.copy(alpha = 0.14f), c.lineStrong)
    }

    @Test
    fun `night-sea raise fills are white tints and fixed shades are black washes`() {
        val white = Color(0xFFFFFFFF)
        assertEquals(white.copy(alpha = 0.02f), c.overlayFaint)
        assertEquals(white.copy(alpha = 0.04f), c.overlaySoft)
        assertEquals(white.copy(alpha = 0.055f), c.overlayMed)
        assertEquals(white.copy(alpha = 0.075f), c.overlayStrong)
        assertEquals(white.copy(alpha = 0.12f), c.hover)
        assertEquals(Color(0xFF1A0B07), c.sunsetInk)
        assertEquals(Color(0xCC1A0E0E), c.bannerScrim)
        // Darkening washes are theme-invariant: black-over works on both palettes.
        assertEquals(Color(0x26000000), c.shade15)
        assertEquals(Color(0x4D000000), c.shade30)
    }

    @Test
    fun `badge fills derive from their accent`() {
        assertEquals(c.sunset.copy(alpha = 0.16f), c.strictBg)
        assertEquals(c.moss.copy(alpha = 0.16f), c.devBg)
        assertEquals(c.amber.copy(alpha = 0.14f), c.amberSoft)
    }

    @Test
    fun `dark color scheme maps tokens to material roles`() {
        val scheme = c.toMaterialColorScheme(dark = true)

        assertEquals(c.cyan, scheme.primary)
        assertEquals(c.bg, scheme.onPrimary)
        assertEquals(c.cyanBright, scheme.secondary)
        // amber is a narrow AI/lighthouse accent, mapped to the tertiary Material role
        assertEquals(c.amber, scheme.tertiary)

        assertEquals(c.bg, scheme.background)
        assertEquals(c.text, scheme.onBackground)
        assertEquals(c.bg, scheme.surface)
        assertEquals(c.text, scheme.onSurface)
        assertEquals(c.surfaceDeep, scheme.surfaceVariant)
        assertEquals(c.dim, scheme.onSurfaceVariant)

        assertEquals(c.storm, scheme.error)
        assertEquals(c.lineStrong, scheme.outline)
        assertEquals(c.line, scheme.outlineVariant)
    }

    @Test
    fun `theme mode round-trips by id and defaults to dark`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromId(it.id)) }
        assertEquals(ThemeMode.DARK, ThemeMode.DEFAULT)
        assertEquals(ThemeMode.DARK, ThemeMode.fromId("nonsense"))
        assertEquals(ThemeMode.entries.size, ThemeMode.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `daybreak is a distinct light palette`() {
        val light = daybreakColors()
        val dark = nightSeaColors()
        // A real palette difference, not an alias.
        assertTrue(light.bg != dark.bg)
        // Light theme: background lighter than text; dark theme: the reverse.
        assertTrue(light.bg.red > light.text.red)
        assertTrue(dark.bg.red < dark.text.red)
        // Borders are a neutral ink tint on light, not the cyan tint used on dark.
        assertTrue(light.line != light.cyan.copy(alpha = 0.06f))
    }

    /**
     * Pins every daybreak token to its reference value so the light palette can't silently drift
     * during the move to XML-sourced colors. Exercises the tricky cases the generator must reproduce
     * exactly: alpha-over-reference borders (`line`/`lineStrong`/`whiteFaint`), 8-digit ARGB
     * literals (`card`/`scrim`/`modalScrim`), and the darkened light-theme accents.
     */
    @Test
    fun `daybreak tokens match the reference values`() {
        val c = daybreakColors()
        val ink = 0xFF0D1E29
        // Surfaces
        assertEquals(Color(0xFFF3F6F8), c.bg)
        assertEquals(Color(0xFFEBF0F3), c.railBg)
        assertEquals(Color(0xFFFFFFFF), c.titleTop)
        assertEquals(Color(0xFFEDF2F5), c.titleBottom)
        assertEquals(Color(0xFFFFFFFF), c.surface)
        assertEquals(Color(0xFFEEF3F6), c.surface2)
        assertEquals(Color(0xFFE1E9EE), c.surfaceDeep)
        assertEquals(Color(0xFFF3F6F8), c.panel)
        assertEquals(Color(0xFFEDF1F4), c.terminalBg)
        // Text ladder (darkest is primary)
        assertEquals(Color(ink), c.text)
        assertEquals(Color(0xFF243B49), c.textBright)
        assertEquals(Color(0xFF3B5361), c.textMid)
        assertEquals(Color(0xFF5E7583), c.dim)
        assertEquals(Color(0xFF6B7E8D), c.faint)
        // Accents — darkened for contrast on light
        assertEquals(Color(0xFF0E90BF), c.cyan)
        assertEquals(Color(0xFF14A6DB), c.cyanBright)
        assertEquals(Color(0xFF2C9E71), c.moss)
        assertEquals(Color(0xFFCE5B3E), c.sunset)
        assertEquals(Color(0xFFB9761B), c.amber)
        assertEquals(Color(0xFFD98E2C), c.amberBright)
        assertEquals(Color(0xFF12A897), c.teal)
        assertEquals(Color(0xFF4FD1C2), c.tealLight)
        assertEquals(Color(0xFF0B7F73), c.tealDeep)
        // Fixed tones
        assertEquals(Color(0xFFFFFFFF), c.white)
        assertEquals(Color(0xFFCE3B3B), c.storm)
        // Borders — neutral ink tint, alpha over the ink base (not baked ARGB)
        assertEquals(Color(ink).copy(alpha = 0.07f), c.line)
        assertEquals(Color(ink).copy(alpha = 0.18f), c.lineStrong)
        // Shared surfaces / accents
        assertEquals(Color(0xFFB4543E), c.strictFg)
        assertEquals(Color(ink).copy(alpha = 0.08f), c.whiteFaint)
        assertEquals(Color(0xFF08303F), c.ink)
        // Raise fills invert to ink tints on light — a white tint would be invisible. Alphas are
        // bumped vs dark (same reasoning as card: ink needs more alpha to read on light surfaces).
        assertEquals(Color(ink).copy(alpha = 0.035f), c.overlayFaint)
        assertEquals(Color(ink).copy(alpha = 0.06f), c.overlaySoft)
        assertEquals(Color(ink).copy(alpha = 0.08f), c.overlayMed)
        assertEquals(Color(ink).copy(alpha = 0.10f), c.overlayStrong)
        assertEquals(Color(ink).copy(alpha = 0.14f), c.hover)
        assertEquals(Color(0xFF38130A), c.sunsetInk)
        assertEquals(Color(0xE6FBF1EE), c.bannerScrim)
        assertEquals(Color(0x0D0D1E29), c.card)
        assertEquals(Color(0x66101922), c.scrim)
        assertEquals(Color(0x73101922), c.modalScrim)
        // Derived getters track the light accents
        assertEquals(c.cyan.copy(alpha = 0.10f), c.cyan10)
        assertEquals(c.sunset.copy(alpha = 0.16f), c.strictBg)
    }

    /**
     * Pins the blackwater palette: a second dark chrome — near-black indigo surfaces with a green
     * accent (the `cyan` token carries the accent ROLE, not the hue). Borders are a neutral white
     * tint, not the accent tint night-sea uses — a green line grid would tint the whole chrome.
     */
    @Test
    fun `blackwater is a green-accent dark palette`() {
        val c = blackwaterColors()
        // Surfaces — indigo-black ladder
        assertEquals(Color(0xFF0E101D), c.bg)
        assertEquals(Color(0xFF0C0E1B), c.railBg)
        assertEquals(Color(0xFF151829), c.titleTop)
        assertEquals(Color(0xFF101221), c.titleBottom)
        assertEquals(Color(0xFF151728), c.surface)
        assertEquals(Color(0xFF181A2B), c.surface2)
        assertEquals(Color(0xFF22263A), c.surfaceDeep)
        assertEquals(Color(0xFF0E111E), c.panel)
        assertEquals(Color(0xFF141527), c.terminalBg)
        // Text
        assertEquals(Color(0xFFE9EAF1), c.text)
        assertEquals(Color(0xFFC9CDDB), c.textBright)
        assertEquals(Color(0xFFABAFC2), c.textMid)
        assertEquals(Color(0xFF8D91A5), c.dim)
        assertEquals(Color(0xFF5D6071), c.faint)
        // Accent role is green
        assertEquals(Color(0xFF20B668), c.cyan)
        assertEquals(Color(0xFF2ED07E), c.cyanBright)
        assertEquals(Color(0xFF35C97F), c.moss)
        assertEquals(Color(0xFFD55B4C), c.sunset)
        assertEquals(Color(0xFFD9A23F), c.amber)
        // Brand teal is unchanged (the logo keeps its identity across themes)
        assertEquals(nightSeaColors().teal, c.teal)
        assertEquals(nightSeaColors().tealLight, c.tealLight)
        assertEquals(nightSeaColors().tealDeep, c.tealDeep)
        // Borders — neutral white tint, not the accent tint
        assertEquals(c.white.copy(alpha = 0.06f), c.line)
        assertEquals(c.white.copy(alpha = 0.14f), c.lineStrong)
        // Ink on the green accent buttons is a deep green-black
        assertEquals(Color(0xFF071A12), c.ink)
        // It's a dark palette
        assertTrue(c.bg.red < c.text.red)
    }

    @Test
    fun `blackwater mode resolves by id and stays a distinct entry`() {
        assertEquals(ThemeMode.BLACKWATER, ThemeMode.fromId("blackwater"))
        assertEquals("blackwater", ThemeMode.BLACKWATER.id)
    }

    /**
     * Pins the signature tokens of every catalog chrome theme (the terminal-theme counterparts).
     * Each palette derives from its canonical upstream colors; full 40-token pins live only for
     * the two stock themes — here the anchors are bg/text/accent/ink plus the dark/light ordering
     * and the invariant brand teal.
     */
    @Test
    fun `catalog chrome themes pin their signature tokens`() {
        data class Pin(val name: String, val c: SkerryColors, val bg: Long, val text: Long, val accent: Long, val ink: Long, val dark: Boolean)
        val pins = listOf(
            Pin("tokyo-night", tokyoNightColors(), 0xFF1A1B26, 0xFFC0CAF5, 0xFF7AA2F7, 0xFF15161E, true),
            Pin("tokyo-day", tokyoDayColors(), 0xFFE1E2E7, 0xFF2D3658, 0xFF2E7DE9, 0xFF14264E, false),
            Pin("catppuccin-mocha", catppuccinMochaColors(), 0xFF1E1E2E, 0xFFCDD6F4, 0xFF89B4FA, 0xFF11111B, true),
            Pin("gruvbox-dark", gruvboxDarkColors(), 0xFF282828, 0xFFEBDBB2, 0xFF83A598, 0xFF1D2021, true),
            Pin("dracula", draculaColors(), 0xFF282A36, 0xFFF8F8F2, 0xFFBD93F9, 0xFF21222C, true),
            Pin("solarized-light", solarizedLightColors(), 0xFFFDF6E3, 0xFF073642, 0xFF268BD2, 0xFF062F41, false),
        )
        for (p in pins) {
            assertEquals(Color(p.bg), p.c.bg, "${p.name} bg")
            assertEquals(Color(p.text), p.c.text, "${p.name} text")
            assertEquals(Color(p.accent), p.c.cyan, "${p.name} accent")
            assertEquals(Color(p.ink), p.c.ink, "${p.name} ink")
            // Dark themes: bg darker than text; light themes: the reverse.
            if (p.dark) assertTrue(p.c.bg.red < p.c.text.red, "${p.name} should be dark") else assertTrue(p.c.bg.red > p.c.text.red, "${p.name} should be light")
            // The logo keeps its brand teal in every theme (light themes use the darkened trio).
            val expectedTeal = if (p.dark) nightSeaColors().teal else daybreakColors().teal
            assertEquals(expectedTeal, p.c.teal, "${p.name} teal")
        }
        // All catalog palettes are real palettes, not aliases of each other.
        val bgs = pins.map { it.c.bg } + nightSeaColors().bg + daybreakColors().bg + blackwaterColors().bg
        assertEquals(bgs.size, bgs.toSet().size)
    }

    /**
     * [ThemeMode.palette]/[ThemeMode.isDark] are the static (non-composable) resolvers used by
     * the theme-picker preview cards; SYSTEM follows the passed OS flag, named modes ignore it.
     */
    @Test
    fun `palette and isDark resolve every mode statically`() {
        assertEquals(nightSeaColors(), ThemeMode.DARK.palette(systemDark = false))
        assertEquals(daybreakColors(), ThemeMode.LIGHT.palette(systemDark = true))
        assertEquals(nightSeaColors(), ThemeMode.SYSTEM.palette(systemDark = true))
        assertEquals(daybreakColors(), ThemeMode.SYSTEM.palette(systemDark = false))
        assertEquals(blackwaterColors(), ThemeMode.BLACKWATER.palette(systemDark = false))
        assertEquals(tokyoDayColors(), ThemeMode.TOKYO_DAY.palette(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.TOKYO_DAY.isDark(systemDark = true))
        assertFalse(ThemeMode.SOLARIZED_LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DRACULA.isDark(systemDark = false))
    }

    /**
     * Unified theming: every app theme names its terminal twin. Catalog modes share ids with the
     * terminal catalog; the stock pair maps to Night Sea / Daybreak; SYSTEM follows the OS side.
     */
    @Test
    fun `every app theme maps to an existing terminal theme`() {
        assertEquals("night-sea", ThemeMode.DARK.terminalThemeId(systemDark = false))
        assertEquals("daybreak", ThemeMode.LIGHT.terminalThemeId(systemDark = true))
        assertEquals("night-sea", ThemeMode.SYSTEM.terminalThemeId(systemDark = true))
        assertEquals("daybreak", ThemeMode.SYSTEM.terminalThemeId(systemDark = false))
        assertEquals("blackwater", ThemeMode.BLACKWATER.terminalThemeId(systemDark = false))
        assertEquals("tokyo-day", ThemeMode.TOKYO_DAY.terminalThemeId(systemDark = true))
    }

    @Test
    fun `theme mode catalog is complete and ids are stable`() {
        assertEquals(10, ThemeMode.entries.size)
        assertEquals(
            listOf("system", "light", "dark", "blackwater", "tokyo-night", "tokyo-day", "catppuccin-mocha", "gruvbox-dark", "dracula", "solarized-light"),
            ThemeMode.entries.map { it.id },
        )
    }

    @Test
    fun `light color scheme maps tokens to material roles`() {
        val c = daybreakColors()
        val scheme = c.toMaterialColorScheme(dark = false)
        assertEquals(c.cyan, scheme.primary)
        assertEquals(c.bg, scheme.background)
        assertEquals(c.text, scheme.onBackground)
        assertEquals(c.storm, scheme.error)
        assertEquals(c.lineStrong, scheme.outline)
    }
}
