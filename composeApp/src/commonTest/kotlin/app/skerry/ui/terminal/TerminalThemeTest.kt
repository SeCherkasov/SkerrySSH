package app.skerry.ui.terminal

import androidx.compose.ui.graphics.Color
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.theme.terminalThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalThemeTest {

    @Test
    fun catalog_has_nine_distinct_themes() {
        val all = TerminalThemes.all
        assertEquals(9, all.size)
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

    /**
     * Golden pin of every built-in terminal theme's background, foreground, cursor, selection and
     * full ANSI palette. Guards the move to XML-sourced themes: the generator must reproduce each
     * theme byte-for-byte, including the ANSI ordering and the two light themes that override
     * selection (Solarized Light, Tokyo Day) versus the translucent-cursor default.
     */
    @Test
    fun every_theme_matches_its_golden_palette() {
        data class Golden(
            val bg: Long,
            val fg: Long,
            val cursor: Long,
            val ansi: List<Long>,
            val selection: Long? = null, // null → default translucent cursor accent
        )

        val golden = mapOf(
            "night-sea" to Golden(
                0xFF050E16, 0xFFE6ECEF, 0xFF2BBDEE,
                listOf(
                    0xFF2A3540, 0xFFE94B4B, 0xFF5DCE9E, 0xFFF2A65A,
                    0xFF4A9EDB, 0xFFC792EA, 0xFF2BBDEE, 0xFFC9D6DE,
                    0xFF5A7080, 0xFFFF6B6B, 0xFF7FE9B8, 0xFFFFC078,
                    0xFF6FC3F5, 0xFFE0A8FF, 0xFF5FD1F4, 0xFFFFFFFF,
                ),
            ),
            "tokyo-night" to Golden(
                0xFF1A1B26, 0xFFC0CAF5, 0xFF7AA2F7,
                listOf(
                    0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                    0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
                    0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                    0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
                ),
            ),
            "tokyo-day" to Golden(
                0xFFE1E2E7, 0xFF3760BF, 0xFF3760BF,
                listOf(
                    0xFFB4B5B9, 0xFFF52A65, 0xFF587539, 0xFF8C6C3E,
                    0xFF2E7DE9, 0xFF9854F1, 0xFF007197, 0xFF6172B0,
                    0xFFA1A6C5, 0xFFF52A65, 0xFF587539, 0xFF8C6C3E,
                    0xFF2E7DE9, 0xFF9854F1, 0xFF007197, 0xFF3760BF,
                ),
                selection = 0xFFB7C1E3,
            ),
            "catppuccin-mocha" to Golden(
                0xFF1E1E2E, 0xFFCDD6F4, 0xFFF5E0DC,
                listOf(
                    0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                    0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
                    0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                    0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8,
                ),
            ),
            "gruvbox-dark" to Golden(
                0xFF282828, 0xFFEBDBB2, 0xFFFE8019,
                listOf(
                    0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
                    0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
                    0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
                    0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
                ),
            ),
            "dracula" to Golden(
                0xFF282A36, 0xFFF8F8F2, 0xFFF8F8F2,
                listOf(
                    0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
                    0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                    0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
                    0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
                ),
            ),
            "daybreak" to Golden(
                0xFFEDF1F4, 0xFF0D1E29, 0xFF0E90BF,
                listOf(
                    0xFFB7C2CA, 0xFFCE3B3B, 0xFF2C9E71, 0xFFB9761B,
                    0xFF1F6FB2, 0xFFB04FA8, 0xFF0E90BF, 0xFF52646F,
                    0xFF93A6B2, 0xFFE05252, 0xFF35B884, 0xFFD98E2C,
                    0xFF3E8CD0, 0xFFC86BC0, 0xFF14A6DB, 0xFF0D1E29,
                ),
                selection = 0xFFC7D8E4,
            ),
            "blackwater" to Golden(
                0xFF141527, 0xFFD6DAE6, 0xFF20B668,
                listOf(
                    0xFF22263A, 0xFFC14146, 0xFF20B668, 0xFFCFA243,
                    0xFF1E86CC, 0xFFC13282, 0xFF10A17D, 0xFFB8BECF,
                    0xFF5D6071, 0xFFE5606A, 0xFF2ED07E, 0xFFE3BC5F,
                    0xFF4EA6E0, 0xFFDB58A4, 0xFF27C69C, 0xFFE9EAF1,
                ),
            ),
            "solarized-light" to Golden(
                0xFFFDF6E3, 0xFF586E75, 0xFF268BD2,
                listOf(
                    0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                    0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                    0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                    0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
                ),
                selection = 0xFFEEE8D5,
            ),
        )

        assertEquals(golden.keys, TerminalThemes.all.map { it.id }.toSet())
        for (theme in TerminalThemes.all) {
            val g = golden.getValue(theme.id)
            assertEquals(Color(g.bg), theme.background, "${theme.id} background")
            assertEquals(Color(g.fg), theme.foreground, "${theme.id} foreground")
            assertEquals(Color(g.cursor), theme.cursor, "${theme.id} cursor")
            assertEquals(g.ansi.map { Color(it) }, theme.ansi, "${theme.id} ansi")
            val expectedSelection = g.selection?.let { Color(it) } ?: theme.cursor.copy(alpha = 0.3f)
            assertEquals(expectedSelection, theme.selection, "${theme.id} selection")
        }
    }

    /** Every app-theme mode must resolve to a real catalog terminal theme (unified theming). */
    @Test
    fun every_theme_mode_maps_into_the_terminal_catalog() {
        for (mode in ThemeMode.entries) {
            for (systemDark in listOf(true, false)) {
                val id = mode.terminalThemeId(systemDark)
                assertEquals(id, TerminalThemes.fromId(id).id, "mode ${mode.id} (systemDark=$systemDark) should map to an existing terminal theme")
            }
        }
    }

    @Test
    fun requiring_sixteen_ansi_colors_is_enforced() {
        val ex = runCatching {
            TerminalTheme("x", "X", Color.Black, Color.White, Color.Cyan, ansi = List(3) { Color.Red })
        }.exceptionOrNull()
        assertNotNull(ex, "the constructor should reject a palette that isn't 16 colors")
    }
}
