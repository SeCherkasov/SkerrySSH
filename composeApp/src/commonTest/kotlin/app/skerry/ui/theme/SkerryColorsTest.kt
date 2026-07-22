package app.skerry.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
