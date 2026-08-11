package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
