package app.skerry.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the picker lists for a given search box — the part of it that is not layout. */
class AiModelPickerTest {

    private val catalog = listOf("gpt-4o", "gpt-4o-mini", "o3", "llama3.1:70b")

    @Test
    fun `an empty query lists the whole catalog in its original order`() {
        assertEquals(catalog, filterAndSortModels(catalog, "", favorites = emptySet()))
        assertEquals(catalog, filterAndSortModels(catalog, "   ", favorites = emptySet()), "a query of spaces is not a filter")
    }

    @Test
    fun `matching is a case-insensitive substring, anywhere in the id`() {
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), filterAndSortModels(catalog, "GPT-4O", favorites = emptySet()))
        assertEquals(listOf("llama3.1:70b"), filterAndSortModels(catalog, "70b", favorites = emptySet()))
        assertEquals(emptyList(), filterAndSortModels(catalog, "claude", favorites = emptySet()))
    }

    @Test
    fun `starred models come first and keep their relative order`() {
        assertEquals(
            listOf("o3", "llama3.1:70b", "gpt-4o", "gpt-4o-mini"),
            filterAndSortModels(catalog, "", favorites = setOf("o3", "llama3.1:70b")),
        )
    }

    @Test
    fun `starring reorders within the filtered set, not around it`() {
        assertEquals(
            listOf("gpt-4o-mini", "gpt-4o"),
            filterAndSortModels(catalog, "gpt", favorites = setOf("gpt-4o-mini", "o3")),
            "a starred model that does not match the query must not be pulled into the list",
        )
    }
}
