package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandSuggestionsTest {

    private val alpha = TerminalHistoryRecord("k-alpha", listOf("docker ps", "uptime"), label = "root@alpha")
    private val beta = TerminalHistoryRecord("k-beta", listOf("docker compose up", "uptime"), label = "dev@beta")

    @Test
    fun merges_history_from_every_host() {
        val all = commandSuggestions(listOf(alpha, beta), currentKey = null, query = "")

        assertEquals(
            setOf("docker ps", "uptime", "docker compose up"),
            all.map { it.command }.toSet(),
        )
    }

    @Test
    fun the_current_host_comes_first_and_is_marked() {
        val all = commandSuggestions(listOf(alpha, beta), currentKey = "k-beta", query = "")

        assertEquals("docker compose up", all.first().command)
        assertTrue(all.first().fromCurrentHost)
        assertTrue(all.last().fromCurrentHost.not())
    }

    @Test
    fun a_command_shared_by_two_hosts_is_listed_once_and_attributed_to_the_current_one() {
        val all = commandSuggestions(listOf(alpha, beta), currentKey = "k-beta", query = "uptime")

        val uptime = all.single { it.command == "uptime" }
        assertEquals("dev@beta", uptime.hostLabel)
        assertTrue(uptime.fromCurrentHost)
    }

    @Test
    fun a_shared_command_falls_back_to_the_first_host_when_none_is_current() {
        val all = commandSuggestions(listOf(alpha, beta), currentKey = null, query = "uptime")

        assertEquals("root@alpha", all.single { it.command == "uptime" }.hostLabel)
    }

    @Test
    fun query_filters_and_ranks_fuzzily() {
        val all = commandSuggestions(listOf(alpha, beta), currentKey = null, query = "dcu")

        assertEquals(listOf("docker compose up"), all.map { it.command })
    }

    @Test
    fun carries_match_positions_for_highlighting() {
        val hit = commandSuggestions(listOf(alpha), currentKey = null, query = "dps").single()

        assertEquals(listOf(0, 7, 8), hit.positions)
    }

    @Test
    fun respects_the_limit() {
        val many = TerminalHistoryRecord("k", (1..50).map { "cmd$it" })

        assertEquals(10, commandSuggestions(listOf(many), currentKey = null, query = "", limit = 10).size)
    }

    @Test
    fun blank_and_duplicate_commands_are_dropped() {
        val messy = TerminalHistoryRecord("k", listOf("ls", "   ", "ls"))

        assertEquals(listOf("ls"), commandSuggestions(listOf(messy), currentKey = null, query = "").map { it.command })
    }

    @Test
    fun no_history_yields_nothing() {
        assertEquals(emptyList(), commandSuggestions(emptyList(), currentKey = null, query = "x"))
    }
}
