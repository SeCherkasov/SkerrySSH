package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatchTest {

    @Test
    fun matches_a_subsequence_not_only_a_substring() {
        assertNotNull(fuzzyScore("docker compose up", "dcu"))
        assertNotNull(fuzzyScore("systemctl status nginx", "sysngx"))
    }

    @Test
    fun rejects_when_a_query_character_is_missing_or_out_of_order() {
        assertNull(fuzzyScore("docker ps", "dockerz"))
        assertNull(fuzzyScore("docker ps", "psd"))
    }

    @Test
    fun is_case_insensitive() {
        assertNotNull(fuzzyScore("Docker PS", "docker ps"))
        assertNotNull(fuzzyScore("docker ps", "DOCKER"))
    }

    @Test
    fun blank_query_matches_everything_with_a_neutral_score() {
        val hit = fuzzyScore("anything", "  ")

        assertNotNull(hit)
        assertEquals(emptyList(), hit.positions)
    }

    @Test
    fun reports_matched_positions_for_highlighting() {
        val hit = assertNotNull(fuzzyScore("docker ps", "dps"))

        assertEquals(listOf(0, 7, 8), hit.positions)
    }

    @Test
    fun consecutive_matches_beat_scattered_ones() {
        val tight = assertNotNull(fuzzyScore("git push", "push"))
        val loose = assertNotNull(fuzzyScore("p-u-s-h-x", "push"))

        assertTrue(tight.score > loose.score, "tight=${tight.score} loose=${loose.score}")
    }

    @Test
    fun word_starts_beat_mid_word_matches() {
        val wordStart = assertNotNull(fuzzyScore("docker system prune", "dsp"))
        val midWord = assertNotNull(fuzzyScore("xdxsxpx", "dsp"))

        assertTrue(wordStart.score > midWord.score, "wordStart=${wordStart.score} midWord=${midWord.score}")
    }

    @Test
    fun a_shorter_candidate_wins_when_the_match_is_otherwise_equal() {
        val short = assertNotNull(fuzzyScore("df -h", "df"))
        val long = assertNotNull(fuzzyScore("df -h | sort -k5 -r | head -20", "df"))

        assertTrue(short.score > long.score)
    }

    @Test
    fun rank_orders_by_score_and_drops_non_matches() {
        val items = listOf("apt update", "docker ps", "docker compose up")

        val ranked = fuzzyRank(items, "docker") { it }

        assertEquals(listOf("docker ps", "docker compose up"), ranked.map { it.item })
    }

    @Test
    fun rank_keeps_input_order_when_scores_tie() {
        // Input is newest-first, so an equal-scoring older command must not jump ahead.
        val items = listOf("ls /tmp", "ls /var")

        val ranked = fuzzyRank(items, "ls /") { it }

        assertEquals(items, ranked.map { it.item })
    }

    @Test
    fun rank_with_a_blank_query_returns_everything_in_order() {
        val items = listOf("b", "a", "c")

        assertEquals(items, fuzzyRank(items, "") { it }.map { it.item })
    }
}
