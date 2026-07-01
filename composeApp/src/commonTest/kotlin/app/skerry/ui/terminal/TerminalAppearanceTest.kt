package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalAppearanceTest {

    @Test
    fun default_is_hack() {
        assertEquals(TerminalFont.Hack, TerminalFont.DEFAULT)
    }

    @Test
    fun fromId_round_trips_every_font() {
        TerminalFont.entries.forEach { font ->
            assertEquals(font, TerminalFont.fromId(font.id))
        }
    }

    @Test
    fun fromId_falls_back_to_default_for_unknown_or_null() {
        assertEquals(TerminalFont.DEFAULT, TerminalFont.fromId("nope"))
        assertEquals(TerminalFont.DEFAULT, TerminalFont.fromId(null))
        assertEquals(TerminalFont.DEFAULT, TerminalFont.fromId(""))
    }

    @Test
    fun default_size_is_within_allowed_range() {
        assertTrue(DEFAULT_TERMINAL_FONT_SIZE in TERMINAL_FONT_SIZE_RANGE)
    }

    @Test
    fun appearance_defaults_match_constants() {
        val a = TerminalAppearance()
        assertEquals(TerminalFont.DEFAULT, a.font)
        assertEquals(DEFAULT_TERMINAL_FONT_SIZE, a.fontSizeSp)
        assertEquals(DEFAULT_TERMINAL_LINE_HEIGHT, a.lineHeight)
        assertEquals(DEFAULT_TERMINAL_LETTER_SPACING, a.letterSpacingSp)
    }

    @Test
    fun clampLineHeight_coerces_into_range_and_rounds() {
        assertEquals(TERMINAL_LINE_HEIGHT_MIN, clampTerminalLineHeight(0.2f))
        assertEquals(TERMINAL_LINE_HEIGHT_MAX, clampTerminalLineHeight(5f))
        assertEquals(1.35f, clampTerminalLineHeight(1.3456f))
    }

    @Test
    fun clampLetterSpacing_coerces_into_range_and_rounds() {
        assertEquals(TERMINAL_LETTER_SPACING_MIN, clampTerminalLetterSpacing(-9f))
        assertEquals(TERMINAL_LETTER_SPACING_MAX, clampTerminalLetterSpacing(9f))
        assertEquals(0.5f, clampTerminalLetterSpacing(0.4967f))
    }
}
