package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TermRowTest {

    private val dot = TermCell('.')

    private fun row(text: String) = TermRow(text.map { TermCell(it) })

    private fun TermRow.text() = joinToString("") { it.text }

    @Test
    fun `insert shifts right and drops what falls off the end`() {
        val r = row("abcde")
        r.insert(1, 2, dot)
        assertEquals("a..bc", r.text())
    }

    @Test
    fun `insert past the remaining width fills to the end`() {
        val r = row("abcde")
        r.insert(3, 99, dot)
        assertEquals("abc..", r.text())
    }

    @Test
    fun `delete shifts left and fills the end`() {
        val r = row("abcde")
        r.delete(1, 2, dot)
        assertEquals("ade..", r.text())
    }

    @Test
    fun `delete past the remaining width clears to the end`() {
        val r = row("abcde")
        r.delete(2, 99, dot)
        assertEquals("ab...", r.text())
    }

    @Test
    fun `fill writes a half-open span`() {
        val r = row("abcde")
        r.fill(dot, 1, 3)
        assertEquals("a..de", r.text())
    }

    @Test
    fun `resize cuts or pads`() {
        val r = row("abc")
        r.resize(5, dot)
        assertEquals("abc..", r.text())
        r.resize(2, dot)
        assertEquals("ab", r.text())
    }

    @Test
    fun `every write drops the snapshot and nothing else does`() {
        val r = row("abc")
        val first = r.snapshot()
        assertSame(first, r.snapshot())
        r.wrapped = false
        assertSame(first, r.snapshot())
        val writes = listOf<TermRow.() -> Unit>(
            { this[0] = dot },
            { fill(TermCell(','), 0, 1) },
            { insert(0, 1, dot) },
            { delete(0, 1, dot) },
            { resize(4, dot) },
            { wrapped = !wrapped },
        )
        var last = r.snapshot()
        for (write in writes) {
            r.write()
            val next = r.snapshot()
            assertNotSame(last, next)
            last = next
        }
    }

    @Test
    fun `a snapshot is a copy the row cannot reach`() {
        val r = row("abc")
        val snap = r.snapshot()
        r[0] = dot
        assertEquals("abc", snap.joinToString("") { it.text })
    }

    @Test
    fun `a fill past cells already set writes the rest`() {
        val r = TermRow(listOf(dot, dot, TermCell('x'), TermCell('y')))
        val first = r.snapshot()
        r.fill(dot, 0, 4)
        assertEquals("....", r.joinToString("") { it.text })
        assertNotSame(first, r.snapshot())
    }
}
