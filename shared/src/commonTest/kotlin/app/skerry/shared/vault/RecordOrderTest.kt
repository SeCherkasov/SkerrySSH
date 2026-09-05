package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored order is a synced record: it can name ids that no longer exist, name one twice, or not
 * name an id at all. None of that may lose or duplicate a record — the order only moves rows.
 */
class RecordOrderTest {

    private data class Row(val id: String)

    private val rows = listOf(Row("a"), Row("b"), Row("c"))

    @Test
    fun `an empty order leaves the list alone`() {
        assertEquals(rows, rows.sortedByOrder(emptyList()) { it.id })
    }

    @Test
    fun `ids the order does not name keep their relative order and land at the end`() {
        assertEquals(
            listOf("c", "a", "b"),
            listOf(Row("a"), Row("b"), Row("c")).sortedByOrder(listOf("c")) { it.id }.map { it.id },
        )
    }

    @Test
    fun `an order naming ids that no longer exist still orders the ones that do`() {
        assertEquals(
            listOf("c", "a", "b"),
            rows.sortedByOrder(listOf("gone", "c", "a", "also-gone", "b")) { it.id }.map { it.id },
        )
    }

    @Test
    fun `a duplicated id in the stored order neither drops nor duplicates a row`() {
        val sorted = rows.sortedByOrder(listOf("c", "a", "c", "b")) { it.id }
        assertEquals(rows.toSet(), sorted.toSet())
        assertEquals(3, sorted.size)
    }
}
