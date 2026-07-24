package app.skerry.ui.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Listing name filter: a blank filter passes everything; a plain string is a case-insensitive
 * substring match; a string with `*`/`?` is a glob over the whole name. Regex metacharacters in
 * the filter are literal (never a syntax error).
 */
class NameFilterTest {

    @Test
    fun blank_filter_matches_everything() {
        assertTrue(matchesNameFilter("nginx.conf", ""))
        assertTrue(matchesNameFilter("nginx.conf", "   "))
    }

    @Test
    fun plain_text_is_a_case_insensitive_substring() {
        assertTrue(matchesNameFilter("nginx.conf", "ngi"))
        assertTrue(matchesNameFilter("nginx.conf", "NGINX"))
        assertTrue(matchesNameFilter("Fastcgi_params", "cgi_PAR"))
        assertFalse(matchesNameFilter("nginx.conf", "apache"))
    }

    @Test
    fun star_glob_matches_the_whole_name() {
        assertTrue(matchesNameFilter("nginx.conf", "*.conf"))
        assertTrue(matchesNameFilter("nginx.CONF", "*.conf"))
        assertFalse(matchesNameFilter("conf.d", "*.conf"))
        assertFalse(matchesNameFilter("nginx.conf.example", "*.conf"))
        assertTrue(matchesNameFilter("nginx.conf.example", "*.conf*"))
    }

    @Test
    fun question_mark_matches_exactly_one_character() {
        assertTrue(matchesNameFilter("koi-utf", "koi-?tf"))
        assertFalse(matchesNameFilter("koi-tf", "koi-?tf"))
    }

    @Test
    fun regex_metacharacters_in_the_filter_are_literal() {
        assertTrue(matchesNameFilter("a+b(c).txt", "a+b(c)"))
        assertFalse(matchesNameFilter("aab", "a+b(c)"))
        assertTrue(matchesNameFilter("data[1].log", "data[1]"))
    }

    @Test
    fun filter_is_trimmed_before_matching() {
        assertTrue(matchesNameFilter("nginx.conf", "  ngi  "))
        assertTrue(matchesNameFilter("nginx.conf", " *.conf "))
    }
}
