package app.skerry.ui.host

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Suggestion logic for the connection form: groups and tags collected from the host catalog so the
 * Group/Tags fields suggest existing values. Groups are stored as entered ([Host.group], trimmed);
 * tags are canonical ([Host.tags]).
 */
class ConnectionFormSuggestionsTest {

    private fun host(
        id: String,
        group: String? = null,
        tags: List<String> = emptyList(),
    ) = Host(id = id, label = id, address = "10.0.0.1", username = "root", group = group, tags = tags)

    @Test
    fun groups_are_distinct_nonblank_in_first_appearance_order() {
        val hosts = listOf(
            host("1", group = "Production"),
            host("2", group = "Staging"),
            host("3", group = "Production"), // duplicate is not repeated
            host("4", group = null), // no group: not suggested
            host("5", group = "   "), // blank after trim: not suggested
        )
        assertEquals(listOf("Production", "Staging"), groupSuggestions(hosts))
    }

    @Test
    fun groups_filtered_by_query_case_insensitive_substring() {
        val hosts = listOf(host("1", group = "Production"), host("2", group = "Staging"))
        assertEquals(listOf("Staging"), groupSuggestions(hosts, "stag"))
        assertEquals(listOf("Production"), groupSuggestions(hosts, "ROD"))
        assertEquals(listOf("Production", "Staging"), groupSuggestions(hosts, "  "))
    }

    @Test
    fun tag_suggestions_exclude_selected_and_dedupe_in_first_appearance_order() {
        val hosts = listOf(
            host("1", tags = listOf("prod", "web")),
            host("2", tags = listOf("docker", "prod")),
        )
        assertEquals(listOf("web", "docker"), tagSuggestions(hosts, selected = listOf("prod")))
    }

    @Test
    fun tag_suggestions_filtered_by_canonicalized_query() {
        val hosts = listOf(host("1", tags = listOf("prod", "web", "docker")))
        // input is canonicalized as a tag: "#DOC" -> "doc" -> substring of "docker"
        assertEquals(listOf("docker"), tagSuggestions(hosts, selected = emptyList(), query = "#DOC"))
        // blank/garbage input: no filter
        assertEquals(listOf("prod", "web", "docker"), tagSuggestions(hosts, selected = emptyList(), query = "#"))
    }
}
