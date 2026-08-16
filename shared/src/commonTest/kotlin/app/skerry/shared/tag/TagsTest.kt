package app.skerry.shared.tag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagsTest {

    @Test
    fun strips_hash_trims_lowercases_blank_to_null() {
        assertEquals("prod", normalizeTag("  #Prod  "))
        assertEquals("docker", normalizeTag("DOCKER"))
        assertEquals("db", normalizeTag("# db"))
        assertNull(normalizeTag("   "))
        assertNull(normalizeTag("#"))
    }

    @Test
    fun strips_hash_from_both_ends() {
        assertEquals("prod", normalizeTag("#prod#"))
        assertEquals("web", normalizeTag("##web##"))
    }

    @Test
    fun truncates_to_max_length() {
        val long = "a".repeat(MAX_TAG_LENGTH + 10)
        assertEquals("a".repeat(MAX_TAG_LENGTH), normalizeTag(long))
    }

    @Test
    fun normalize_tags_drops_blanks_and_collapses_case_duplicates() {
        assertEquals(listOf("prod", "db"), normalizeTags(listOf("DB", "  ", "#db", "Prod")))
    }

    @Test
    fun normalize_tags_keeps_first_seen_order() {
        assertEquals(listOf("web", "db", "cache"), normalizeTags(listOf("Web", "DB", "cache", "web")))
    }

    @Test
    fun normalize_tags_hoists_prod_to_the_front() {
        assertEquals(listOf("prod", "web", "db"), normalizeTags(listOf("Web", "db", "#PROD")))
        assertEquals(listOf("prod", "web"), normalizeTags(listOf("prod", "web")))
    }

    @Test
    fun order_tags_prod_first_keeps_the_rest_untouched() {
        assertEquals(listOf("prod", "web", "db"), orderTagsProdFirst(listOf("web", "prod", "db")))
        assertEquals(listOf("web", "db"), orderTagsProdFirst(listOf("web", "db")))
        assertEquals(emptyList(), orderTagsProdFirst(emptyList()))
    }

    @Test
    fun normalize_tags_keeps_prod_when_the_cap_is_hit() {
        // `prod` arms the production guard: it must not be the tag that falls off a long list.
        val many = (1..MAX_TAGS_PER_RECORD + 5).map { "tag$it" } + "prod"
        val out = normalizeTags(many)
        assertEquals("prod", out.first())
        assertEquals(MAX_TAGS_PER_RECORD, out.size)
    }

    @Test
    fun normalize_tags_caps_the_count() {
        val many = (1..MAX_TAGS_PER_RECORD + 5).map { "tag$it" }
        assertEquals(MAX_TAGS_PER_RECORD, normalizeTags(many).size)
        assertEquals("tag1", normalizeTags(many).first())
    }

    /**
     * A tag decides whether the production guard fires, and the guard compares literally: stored
     * with a zero-width character in it, a host would read as `#prod` and never be protected.
     */
    @Test
    fun `a tag is stored the way it draws`() {
        assertEquals("prod", normalizeTag("pro\u200Bd"))
        assertEquals("prod", normalizeTag("pro\u2062d"))
        assertEquals("prod", normalizeTag("prod\uDB40\uDC41"))
    }

    /**
     * Half a character is not a character: kept, the two orphans either side of a dropped pair join
     * into the very code point that was dropped, and the tag draws as `prod` again while comparing
     * as something else.
     */
    @Test
    fun `a lone surrogate cannot reassemble into an invisible character`() {
        assertEquals("prod", normalizeTag("pro\uD834\uDB40\uDC41\uDD73d"))
        assertEquals("prod", normalizeTag("pro\uD834d"))
    }

    /**
     * A variation selector and its relatives are marks, not format characters, so a category rule
     * that asks only about FORMAT keeps them — and a tag carrying one reads as `prod` and matches
     * nothing.
     */
    @Test
    fun `a mark that draws as nothing is dropped too`() {
        assertEquals("prod", normalizeTag("pro\uFE0Fd"))
        assertEquals("prod", normalizeTag("pro\u034Fd"))
        assertEquals("prod", normalizeTag("prod\u180B"))
    }

    /** A separator ends a line wherever it is drawn: one tag would draw as several. */
    @Test
    fun `a line separator inside a tag is dropped`() {
        assertEquals("prod", normalizeTag("pro\u2028d"))
    }

    /**
     * What the filter approximates is Unicode's `Default_Ignorable_Code_Point`, and the shaper under
     * both targets hides exactly that set with a zero advance. Part of it names no category that
     * says so: unassigned holes reserved as ignorable, and marks outside the variation selectors.
     * A rule written per category keeps them and the tag draws as `prod` while matching nothing.
     */
    @Test
    fun `a code point the shaper hides is dropped whatever its category says`() {
        assertEquals("prod", normalizeTag("pro\uFFF0d")) // reserved, drawn as nothing
        assertEquals("prod", normalizeTag("pro\u2065d")) // the hole inside the format block
        assertEquals("prod", normalizeTag("pro\u17B4d")) // Khmer inherent vowel, Mn
        assertEquals("prod", normalizeTag("prod\uDB41\uDD00")) // plane 14, past the tag block
        assertEquals("prod", normalizeTag("prod\u180F")) // the fourth Mongolian free variation selector
        assertEquals("prod", normalizeTag("prod\uD81B\uDFE4")) // Khitan small script filler
    }

    /**
     * The cap counts UTF-16 units, so it can fall between the halves of an astral character. Left
     * there, the orphan draws as nothing — two tags that differ only by it would draw as one chip
     * and filter two different sets.
     */
    @Test
    fun `the cut is not made through a surrogate pair`() {
        val cut = normalizeTag("a".repeat(MAX_TAG_LENGTH - 1) + "\uD83D\uDE80")
        assertEquals("a".repeat(MAX_TAG_LENGTH - 1), cut)
    }

    /** A tag padded past any use is not read past the bound — the scan is the far side's to give. */
    @Test
    fun `a flood of invisible characters is not scanned past the bound`() {
        assertEquals(null, normalizeTag("\u200B".repeat(50_000) + "prod"))
        assertEquals("prod", normalizeTag("\u200B".repeat(100) + "prod"))
    }
}
