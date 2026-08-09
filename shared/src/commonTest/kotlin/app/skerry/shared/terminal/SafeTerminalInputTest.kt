package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two predicates untrusted text passes through: one for text that may be executed, one for text
 * that is only shown. They differ on exactly one range, and that difference is the point — asserted
 * character by character here rather than inferred from the parsers that use them.
 */
class SafeTerminalInputTest {

    @Test
    fun `both reject control bytes, DEL and the C1 range`() {
        listOf(0x00, 0x07, 0x0A, 0x0D, 0x1B, 0x1F, 0x7F, 0x80, 0x9F).forEach { code ->
            assertFalse(isSafeTerminalInputChar(code.toChar()), "input allowed U+${code.toString(16)}")
            assertFalse(isSafeDisplayChar(code.toChar()), "display allowed U+${code.toString(16)}")
        }
    }

    @Test
    fun `both keep tab and ordinary text`() {
        listOf('\t', 'a', 'Я', '中', '/', '-').forEach { char ->
            assertTrue(isSafeTerminalInputChar(char), "input rejected `$char`")
            assertTrue(isSafeDisplayChar(char), "display rejected `$char`")
        }
    }

    @Test
    fun `both reject everything that reorders a line`() {
        val reordering = (0x202A..0x202E) + (0x2066..0x2069) + listOf(0x061C, 0x2028, 0x2029)
        reordering.forEach { code ->
            assertFalse(isSafeTerminalInputChar(code.toChar()), "input allowed U+${code.toString(16)}")
            assertFalse(isSafeDisplayChar(code.toChar()), "display allowed U+${code.toString(16)}")
        }
    }

    /**
     * The whole difference between the two, spelled out: a command must not carry an invisible
     * character at all, while prose needs the joiners to render a family emoji or a Persian word,
     * and the direction marks to write mixed-direction text — none of which reorder anything.
     */
    @Test
    fun `only the display predicate keeps the invisible characters that reorder nothing`() {
        val invisible = (0x200B..0x200F) + listOf(0x2060, 0xFEFF)
        invisible.forEach { code ->
            assertFalse(isSafeTerminalInputChar(code.toChar()), "input allowed U+${code.toString(16)}")
            assertTrue(isSafeDisplayChar(code.toChar()), "display rejected U+${code.toString(16)}")
        }
    }

    @Test
    fun `the soft hyphen is an input hazard but harmless to show`() {
        assertFalse(isSafeTerminalInputChar('­'))
        assertTrue(isSafeDisplayChar('­'))
    }
}
