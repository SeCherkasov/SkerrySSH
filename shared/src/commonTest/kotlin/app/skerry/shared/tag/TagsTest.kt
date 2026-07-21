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
        assertEquals(listOf("db", "prod"), normalizeTags(listOf("DB", "  ", "#db", "Prod")))
    }

    @Test
    fun normalize_tags_keeps_first_seen_order() {
        assertEquals(listOf("web", "db", "cache"), normalizeTags(listOf("Web", "DB", "cache", "web")))
    }

    @Test
    fun normalize_tags_caps_the_count() {
        val many = (1..MAX_TAGS_PER_RECORD + 5).map { "tag$it" }
        assertEquals(MAX_TAGS_PER_RECORD, normalizeTags(many).size)
        assertEquals("tag1", normalizeTags(many).first())
    }
}
