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
}
