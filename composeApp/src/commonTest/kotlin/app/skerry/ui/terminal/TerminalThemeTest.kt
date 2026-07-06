package app.skerry.ui.terminal

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalThemeTest {

    @Test
    fun catalog_has_seven_distinct_themes() {
        val all = TerminalThemes.all
        assertEquals(7, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size, "theme ids should be unique")
        assertEquals(all.size, all.map { it.displayName }.toSet().size, "theme names should be unique")
    }

    @Test
    fun default_is_night_sea() {
        assertSame(TerminalThemes.NightSea, TerminalThemes.DEFAULT)
        assertEquals("night-sea", TerminalThemes.DEFAULT.id)
    }

    @Test
    fun every_theme_has_exactly_sixteen_ansi_colors() {
        for (theme in TerminalThemes.all) {
            assertEquals(16, theme.ansi.size, "theme ${theme.id} should have 16 ANSI colors")
        }
    }

    @Test
    fun fromId_roundtrips_every_theme() {
        for (theme in TerminalThemes.all) {
            assertSame(theme, TerminalThemes.fromId(theme.id))
        }
    }

    @Test
    fun fromId_falls_back_to_default_on_unknown_or_null() {
        assertSame(TerminalThemes.DEFAULT, TerminalThemes.fromId("does-not-exist"))
        assertSame(TerminalThemes.DEFAULT, TerminalThemes.fromId(null))
        assertSame(TerminalThemes.DEFAULT, TerminalThemes.fromId(""))
    }

    /**
     * "Night Sea" must exactly reproduce the renderer's previously hardcoded palette (background
     * #050E16, foreground #E6ECEF, cursor #2BBDEE, plus the legacy ANSI 0..15), or changing the
     * default would silently recolor existing sessions.
     */
    @Test
    fun night_sea_matches_legacy_hardcoded_palette() {
        val t = TerminalThemes.NightSea
        assertEquals(Color(0xFF050E16), t.background)
        assertEquals(Color(0xFFE6ECEF), t.foreground)
        assertEquals(Color(0xFF2BBDEE), t.cursor)
        assertEquals(t.background, t.cursorText)
        val legacy = listOf(
            0xFF2A3540, 0xFFE94B4B, 0xFF5DCE9E, 0xFFF2A65A,
            0xFF4A9EDB, 0xFFC792EA, 0xFF2BBDEE, 0xFFC9D6DE,
            0xFF5A7080, 0xFFFF6B6B, 0xFF7FE9B8, 0xFFFFC078,
            0xFF6FC3F5, 0xFFE0A8FF, 0xFF5FD1F4, 0xFFFFFFFF,
        ).map { Color(it) }
        assertEquals(legacy, t.ansi)
    }

    @Test
    fun selection_is_translucent_cursor_accent() {
        val t = TerminalThemes.NightSea
        assertEquals(t.cursor.copy(alpha = 0.3f), t.selection)
        assertTrue(t.selection.alpha < 1f)
    }

    @Test
    fun requiring_sixteen_ansi_colors_is_enforced() {
        val ex = runCatching {
            TerminalTheme("x", "X", Color.Black, Color.White, Color.Cyan, ansi = List(3) { Color.Red })
        }.exceptionOrNull()
        assertNotNull(ex, "the constructor should reject a palette that isn't 16 colors")
    }
}
