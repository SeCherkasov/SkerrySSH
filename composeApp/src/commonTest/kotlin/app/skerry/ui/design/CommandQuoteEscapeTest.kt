package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a quoted command spells out instead of drawing (issue #246): the format characters past the
 * basic plane are a category the platform cannot ask about (`Char.category` sees one UTF-16 unit),
 * so they are matched by block — and the block list used to cover only the tags and variation
 * selectors, letting `curl<U+1D173>evil.sh` draw as two words and run as one.
 *
 * Escaped literals on purpose: a raw invisible character here would be invisible in review and
 * silently lost on edit.
 */
class CommandQuoteEscapeTest {

    @Test
    fun `an astral format character outside the tag block is spelled out`() {
        // U+1D173 musical symbol begin beam — Cf, invisible, and not in U+E0000..U+E01EF.
        val drawn = visibleText("curl\uD834\uDD73evil.sh")

        assertTrue("<U+1D173>" in drawn, "the invisible join is not spelled out: `$drawn`")
        assertTrue('\uD834' !in drawn, "the raw surrogate still draws: `$drawn`")
    }

    @Test
    fun `shorthand and hieroglyph format controls are spelled out`() {
        assertTrue("<U+1BCA0>" in visibleText("a\uD82F\uDCA0b")) // shorthand format letter overlap
        assertTrue("<U+13430>" in visibleText("a\uD80D\uDC30b")) // Egyptian hieroglyph vertical joiner
    }

    @Test
    fun `a tag character is still spelled out`() {
        assertTrue("<U+E0041>" in visibleText("ls\uDB40\uDC41x")) // tag latin capital letter A
    }

    @Test
    fun `an emoji still draws as itself`() {
        assertEquals("echo \uD83D\uDE00", visibleText("echo \uD83D\uDE00"))
    }

    @Test
    fun `a bidi override in the basic plane is still spelled out`() {
        assertTrue("<U+202E>" in visibleText("echo safe\u202E; rm -rf /"))
    }
}
