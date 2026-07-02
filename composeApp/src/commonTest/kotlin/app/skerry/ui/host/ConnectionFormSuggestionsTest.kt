package app.skerry.ui.host

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чистая логика подсказок для формы коннекта: существующие группы и теги, собранные из каталога
 * хостов, чтобы поля «Group»/«Tags» предлагали уже созданные значения (а не только свободный ввод).
 * Группы хранятся как введены ([Host.group], trim), теги — в канонической форме ([Host.tags]).
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
            host("3", group = "Production"), // дубль не повторяется
            host("4", group = null), // без группы — не предлагается
            host("5", group = "   "), // пустая после trim — не предлагается
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
        // ввод канонизируется как тег: «#DOC» → «doc» → подстрока «docker»
        assertEquals(listOf("docker"), tagSuggestions(hosts, selected = emptyList(), query = "#DOC"))
        // пустой/мусорный ввод — без фильтра
        assertEquals(listOf("prod", "web", "docker"), tagSuggestions(hosts, selected = emptyList(), query = "#"))
    }
}
