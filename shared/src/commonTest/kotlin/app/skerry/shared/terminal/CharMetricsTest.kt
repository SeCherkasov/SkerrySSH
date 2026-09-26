package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Focused tests of the pure [CharMetrics] API; grid behavior is covered via TerminalEmulatorTest. */
class CharMetricsTest {

    @Test
    fun `printable ascii resolves to one shared instance per char`() {
        // feed() calls codePointToString once per printed character: streaming plain text must
        // not allocate a fresh one-char String per byte.
        assertSame(CharMetrics.codePointToString('a'.code), CharMetrics.codePointToString('a'.code))
        assertSame(CharMetrics.codePointToString(0x20), CharMetrics.codePointToString(0x20))
        assertSame(CharMetrics.codePointToString(0x7E), CharMetrics.codePointToString(0x7E))
        assertEquals("a", CharMetrics.codePointToString('a'.code))
        assertEquals("~", CharMetrics.codePointToString(0x7E))
    }

    @Test
    fun `box-drawing glyphs resolve to one shared instance per char`() {
        // TUI borders (tmux, mc, htop) repeat these across whole rows.
        assertSame(CharMetrics.codePointToString(0x2500), CharMetrics.codePointToString(0x2500))
        assertSame(CharMetrics.codePointToString(0x257F), CharMetrics.codePointToString(0x257F))
        assertEquals("\u2500", CharMetrics.codePointToString(0x2500))
    }

    @Test
    fun `table boundaries fall through to the general branch`() {
        assertEquals("\u001F", CharMetrics.codePointToString(0x1F))
        assertEquals("\u007F", CharMetrics.codePointToString(0x7F))
        assertEquals("\u24FF", CharMetrics.codePointToString(0x24FF))
        assertEquals("\u2580", CharMetrics.codePointToString(0x2580))
    }

    @Test
    fun `width is 2 for CJK and emoji, 1 for latin`() {
        assertEquals(2, CharMetrics.charWidth(0x4E2D)) // 中
        assertEquals(2, CharMetrics.charWidth(0x1F600)) // 😀
        assertEquals(1, CharMetrics.charWidth('a'.code))
    }

    @Test
    fun `combining marks and ZWJ are combining, letters are not`() {
        assertTrue(CharMetrics.isCombining(0x0301)) // acute accent
        assertTrue(CharMetrics.isCombining(0x200D)) // ZWJ
        assertFalse(CharMetrics.isCombining('e'.code))
    }

    @Test
    fun `emoji with default emoji presentation outside the pictograph blocks are wide`() {
        assertEquals(2, CharMetrics.charWidth(0x2705)) // ✅
        assertEquals(2, CharMetrics.charWidth(0x26A1)) // ⚡
        assertEquals(2, CharMetrics.charWidth(0x231A)) // ⌚
        assertEquals(2, CharMetrics.charWidth(0x2B1B)) // ⬛
        assertEquals(2, CharMetrics.charWidth(0x1F97A)) // 🥺
    }

    @Test
    fun `text-presentation symbols and ambiguous characters stay narrow`() {
        assertEquals(1, CharMetrics.charWidth(0x2764)) // ❤ without VS16
        assertEquals(1, CharMetrics.charWidth(0x2713)) // ✓
        assertEquals(1, CharMetrics.charWidth(0x00B1)) // ± (ambiguous)
        assertEquals(1, CharMetrics.charWidth(0xFF61)) // halfwidth ideographic full stop
        assertEquals(1, CharMetrics.charWidth(0x1F1E6)) // regional indicator A
    }

    @Test
    fun `CJK extensions past the BMP and the ideographic space are wide`() {
        assertEquals(2, CharMetrics.charWidth(0x3000)) // ideographic space
        assertEquals(2, CharMetrics.charWidth(0x30000)) // CJK Ext G
        assertEquals(2, CharMetrics.charWidth(0x2A700)) // CJK Ext C
    }

    @Test
    fun `marks and format characters of every script join the cell before`() {
        assertTrue(CharMetrics.isCombining(0x094D)) // Devanagari virama
        assertTrue(CharMetrics.isCombining(0x0E48)) // Thai tone mark
        assertTrue(CharMetrics.isCombining(0x200B)) // zero-width space
        assertTrue(CharMetrics.isCombining(0x2060)) // word joiner
        assertTrue(CharMetrics.isCombining(0xFEFF)) // BOM / ZWNBSP
        assertTrue(CharMetrics.isCombining(0xE0041)) // tag latin A
    }

    @Test
    fun `characters that draw are not joined`() {
        assertFalse(CharMetrics.isCombining(0x00AD)) // soft hyphen
        assertFalse(CharMetrics.isCombining(0x0600)) // Arabic number sign
        assertFalse(CharMetrics.isCombining(0x1160)) // Hangul jungseong filler — a letter here
        assertFalse(CharMetrics.isCombining(0x0915)) // Devanagari KA
    }

    @Test
    fun `codePointToString handles BMP, astral and invalid`() {
        assertEquals("A", CharMetrics.codePointToString(0x41))
        assertEquals("😀", CharMetrics.codePointToString(0x1F600)) // surrogate pair
        assertEquals("�", CharMetrics.codePointToString(0xD800))  // lone surrogate — invalid
    }
}
