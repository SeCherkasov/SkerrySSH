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

    /**
     * The bounded form is what a row draws where there is no space for the quote itself. Spelling a
     * character out makes the string longer than what it came from, so text made of nothing else
     * would draw as several times its own size — both ends are cut, and neither cut lands inside an
     * escape.
     */
    @Test
    fun `the bounded form keeps a short command exactly as the quote would`() {
        assertEquals(visibleText("echo ok"), boundedVisibleText("echo ok"))
        assertEquals("echo <U+202E>ok", boundedVisibleText("echo \u202Eok"))
    }

    @Test
    fun `the bounded form caps what a row can be made to draw`() {
        val flood = "\u202E".repeat(MAX_DRAWN_COMMAND_CHARS)
        val drawn = boundedVisibleText(flood)
        assertTrue(drawn.length <= MAX_DRAWN_COMMAND_CHARS, "drew ${drawn.length} characters")
        // Cut between escapes, never inside one: half a token is neither the character nor its name.
        assertTrue(drawn.endsWith(">"), "the cut landed inside an escape: ...${drawn.takeLast(12)}")
    }

    /**
     * The supplement is not the same thing as the sixteen below: 240 invisible code points nothing
     * in a shell line needs, and a known way to carry a payload through text a human approved.
     */
    @Test
    fun `an astral variation selector is spelled out`() {
        assertEquals("echo ok<U+E0100>", visibleText("echo ok\uDB40\uDD00"))
    }

    /**
     * A variation selector is not a hidden character: it picks the emoji form of the glyph before
     * it. Spelled out, every ordinary `⚠️` in a confirmed command would read as `⚠<U+FE0F>`.
     */
    @Test
    fun `a variation selector attached to a glyph is left alone`() {
        assertEquals("echo \"\u26A0\uFE0F disk full\"", visibleText("echo \"\u26A0\uFE0F disk full\""))
        assertEquals("ok \uD83D\uDC68\uD83C\uDFFB", visibleText("ok \uD83D\uDC68\uD83C\uDFFB"))
    }
}
