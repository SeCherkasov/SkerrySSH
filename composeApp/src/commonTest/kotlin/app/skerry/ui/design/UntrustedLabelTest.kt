package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The filter every label of foreign origin passes through — a remote file name, a shared profile, a
 * team space. What each screen does with the result is covered where it is drawn (the listing and
 * the host rows); what is pinned here is the shape of the string that comes out.
 *
 * Written as escapes, never as the characters themselves: they are invisible in a diff, and a
 * reviewer could not tell the fixture from the expectation.
 */
class UntrustedLabelTest {

    @Test
    fun `a bidi override is dropped`() {
        val label = untrustedLabel("invoice\u202Egnp.exe")
        assertEquals("invoicegnp.exe", label)
        assertFalse(label.any { it.category == CharCategory.FORMAT })
    }

    @Test
    fun `the zero-width formatters go too`() {
        assertEquals("report.log", untrustedLabel("re\u200Bport\u200D.log\uFEFF"))
    }

    /** Control bytes as well: a one-line label has no use for a newline, and none for a NUL. */
    @Test
    fun `control characters are flattened or dropped`() {
        assertEquals("a b", untrustedLabel("a\nb"))
        assertEquals("ab", untrustedLabel("a\u0000b"))
    }

    @Test
    fun `a name longer than the cap is cut to it`() {
        assertEquals(MAX_UNTRUSTED_LABEL_CHARS, untrustedLabel("o".repeat(5_000)).length)
    }

    /**
     * The cap counts UTF-16 units, so it can fall between the halves of an astral character; the
     * label would then draw U+FFFD in place of the character the peer typed.
     */
    @Test
    fun `the cut is not made through a surrogate pair`() {
        val label = untrustedLabel("o".repeat(MAX_UNTRUSTED_LABEL_CHARS - 1) + "🚀")
        assertEquals(MAX_UNTRUSTED_LABEL_CHARS - 1, label.length)
        assertFalse(label.any { it.isSurrogate() }, "half of the astral character was left in the label")
    }

    /** A pair that fits stays whole — the cut is the only thing that may take one apart. */
    @Test
    fun `an astral character inside the cap survives`() {
        assertEquals("ops 🚀", untrustedLabel("ops 🚀"))
    }

    /**
     * The scan is bounded by the input, not only by what it keeps: a name padded with a million
     * characters that draw as nothing must not cost a million comparisons. Past the bound the tail
     * is never looked at — a name written that way is not one.
     */
    @Test
    fun `a flood of invisible characters is not scanned past the bound`() {
        assertEquals("", untrustedLabel("\u200B".repeat(50_000) + "prod"))
    }

    /** Just inside the bound the same tail survives — the cut is the flood's, not everyone's. */
    @Test
    fun `formatting inside the bound does not cost the tail`() {
        assertEquals("prod", untrustedLabel("\u200B".repeat(100) + "prod"))
    }

    /**
     * Letters and a symbol by category, nothing at all on screen. They are not format characters,
     * so `isBlank()` says such a name is a name — and the stand-in a row falls back to would never
     * fire. Dropping the three closes that, and nothing wider: homoglyphs are a different problem.
     */
    @Test
    fun `the invisible letters are dropped so a nameless name reads as blank`() {
        assertEquals("", untrustedLabel("\u3164\u115F\u2800"))
        assertEquals("ops", untrustedLabel("ops\u3164"))
    }

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Платформа · прод", untrustedLabel("Платформа · прод"))
    }

    /** Filtering an already filtered label changes nothing — the sinks stack the call. */
    @Test
    fun `the filter is idempotent`() {
        val once = untrustedLabel("web\u202E10-")
        assertEquals(once, untrustedLabel(once))
    }

    /**
     * By code point: both halves of an astral formatting character classify as SURROGATE, so a
     * per-char filter keeps the whole range and two names differing only by one draw as one.
     */
    @Test
    fun `an astral formatting character is dropped like its basic-plane siblings`() {
        assertEquals("deploy", untrustedLabel("deploy\uDB40\uDC41")) // tag latin capital letter A
        assertEquals("deploy", untrustedLabel("dep\uD834\uDD73loy")) // musical symbol beam
    }

    @Test
    fun `an astral character that draws as itself survives`() {
        assertEquals("deploy \uD83D\uDE00", untrustedLabel("deploy \uD83D\uDE00"))
    }

    @Test
    fun `a lone surrogate is dropped rather than drawn as a replacement glyph`() {
        assertEquals("deploy", untrustedLabel("dep\uD834loy"))
        assertEquals("deploy", untrustedLabel("dep\uD834\uDB40\uDC41\uDD73loy"))
    }

    /**
     * Marks, not format characters: a category rule that asks only about FORMAT keeps them, and a
     * name carrying one draws exactly like the name without it.
     */
    @Test
    fun `a mark that draws as nothing is dropped`() {
        assertEquals("deploy", untrustedLabel("dep\uFE0Floy"))
        assertEquals("deploy", untrustedLabel("dep\u034Floy"))
    }

    /**
     * A note is cut whether or not the cut is visible in what came back. The last character the
     * budget bought can be a newline, and the trailing trim takes it away — so the drawn string ends
     * one under the cap and a length comparison calls a shortened note whole.
     */
    @Test
    fun `a cut that lands on a separator is still a cut`() {
        val cap = 10
        // Both sides of the boundary: the separator inside the cap, and the separator exactly at it.
        // A budget with one character of slack answers the first and not the second — the walk knows
        // whether it stopped on the text or on the budget, and that is what is asked.
        listOf("A".repeat(cap - 1) + "\n" + "the rest", "A".repeat(cap) + "\n" + "the rest").forEach { note ->
            assertTrue(sanitizeServerText(note, cap, allowNewlines = true).length <= cap)
            assertFalse(sanitizedFits(note, cap, allowNewlines = true), "a note cut on its newline read as whole")
        }
        assertTrue(sanitizedFits("A".repeat(cap), cap, allowNewlines = true), "a note that fits read as cut")
        // Filtering is not cutting: a note shortened only by what draws as nothing is shown whole.
        assertTrue(sanitizedFits("A".repeat(cap - 1) + "\u200B".repeat(20), cap, allowNewlines = true))
    }
}
