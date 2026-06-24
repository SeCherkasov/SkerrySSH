package app.skerry.shared.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostTagsTest {

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
}
