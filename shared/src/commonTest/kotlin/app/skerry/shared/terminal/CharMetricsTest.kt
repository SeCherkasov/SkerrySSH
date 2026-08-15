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
    fun `codePointToString handles BMP, astral and invalid`() {
        assertEquals("A", CharMetrics.codePointToString(0x41))
        assertEquals("😀", CharMetrics.codePointToString(0x1F600)) // surrogate pair
        assertEquals("�", CharMetrics.codePointToString(0xD800))  // lone surrogate — invalid
    }
}
