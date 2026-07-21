package app.skerry.ui.snippet

import app.skerry.shared.tag.MAX_TAGS_PER_RECORD
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetTagsTest {

    @Test
    fun splits_on_comma_space_newline_and_tab() {
        assertEquals(listOf("db", "disk", "net", "docker"), parseSnippetTags("db, disk\nnet\tdocker"))
    }

    @Test
    fun canonicalizes_casing_and_hashes_like_host_tags() {
        assertEquals(listOf("db"), parseSnippetTags("#DB"))
        assertEquals(listOf("prod"), parseSnippetTags("#prod#"))
    }

    @Test
    fun collapses_duplicates_that_differ_only_in_case() {
        assertEquals(listOf("db"), parseSnippetTags("DB, db, #Db"))
    }

    @Test
    fun drops_empties() {
        assertEquals(emptyList(), parseSnippetTags("  ,  # ,\n"))
    }

    @Test
    fun caps_the_tag_count() {
        val many = (1..MAX_TAGS_PER_RECORD + 5).joinToString(",") { "tag$it" }
        assertEquals(MAX_TAGS_PER_RECORD, parseSnippetTags(many).size)
    }
}
