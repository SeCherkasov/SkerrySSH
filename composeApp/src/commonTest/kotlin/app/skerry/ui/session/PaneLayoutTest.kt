package app.skerry.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaneLayoutTest {

    // Shape as "row1|row2" with panes separated by commas — reads like the grid on screen.
    private fun shapeOf(layout: PaneLayout): String =
        layout.rows.joinToString("|") { row -> row.cells.joinToString(",") { it.paneId } }

    private fun weightsSum(values: List<Float>): Float = values.sum()

    // Weights are computed by repeated scaling, so they land within a float ULP of the exact share.
    private fun assertWeights(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size, "weight count")
        expected.forEachIndexed { i, e -> assertEquals(e, actual[i], 1e-4f, "weight $i") }
    }

    @Test
    fun `a fresh layout is one pane in one row`() {
        val layout = PaneLayout.of("a")
        assertEquals("a", shapeOf(layout))
        assertEquals(listOf("a"), layout.paneIds)
        assertEquals(1, layout.size)
        assertEquals(1f, layout.rows.single().weight)
        assertEquals(1f, layout.rows.single().cells.single().weight)
    }

    @Test
    fun `adding into a new row stacks the panes and splits the height evenly`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        assertEquals("a|b", shapeOf(layout))
        assertWeights(listOf(0.5f, 0.5f), layout.rows.map { it.weight })
    }

    @Test
    fun `adding into an existing row splits that row's width evenly`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.InRow(row = 0, column = 1))
        assertEquals("a,b", shapeOf(layout))
        assertEquals(listOf(1f), layout.rows.map { it.weight })
        assertWeights(listOf(0.5f, 0.5f), layout.rows.single().cells.map { it.weight })
    }

    @Test
    fun `adding at column zero puts the pane first in the row`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.InRow(row = 0, column = 0))
        assertEquals("b,a", shapeOf(layout))
    }

    @Test
    fun `two on top and one below is reachable`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.NewRow(1))
        assertEquals("a,b|c", shapeOf(layout))
        assertWeights(listOf(0.5f, 0.5f), layout.rows.map { it.weight })
    }

    @Test
    fun `one on top and three below is reachable`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .add("c", PaneSlot.InRow(row = 1, column = 1))
            .add("d", PaneSlot.InRow(row = 1, column = 2))
        assertEquals("a|b,c,d", shapeOf(layout))
        assertWeights(listOf(1 / 3f, 1 / 3f, 1 / 3f), layout.rows[1].cells.map { it.weight })
    }

    @Test
    fun `the layout is full at MAX_PANES and further adds are refused`() {
        val full = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.NewRow(1))
            .add("d", PaneSlot.InRow(row = 1, column = 1))
        assertEquals(MAX_PANES, full.size)
        assertTrue(full.isFull)
        val refused = full.add("e", PaneSlot.NewRow(2))
        assertSame(full, refused)
    }

    @Test
    fun `adding a pane that is already placed is refused`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        assertSame(layout, layout.add("b", PaneSlot.NewRow(0)))
    }

    @Test
    fun `an out-of-range slot appends instead of throwing`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(9))
        assertEquals("a|b", shapeOf(layout))
        val inRow = PaneLayout.of("a").add("b", PaneSlot.InRow(row = 7, column = 9))
        assertEquals("a,b", shapeOf(inRow))
    }

    @Test
    fun `removing a pane redistributes its row's width`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.InRow(row = 0, column = 2))
        val after = layout.remove("b")
        assertEquals("a,c", shapeOf(after))
        assertEquals(1f, weightsSum(after.rows.single().cells.map { it.weight }))
    }

    @Test
    fun `removing the last pane of a row drops the row and redistributes the height`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .add("c", PaneSlot.NewRow(2))
        val after = layout.remove("b")
        assertEquals("a|c", shapeOf(after))
        assertWeights(listOf(0.5f, 0.5f), after.rows.map { it.weight })
    }

    @Test
    fun `removing the only pane is refused - a tab always has one`() {
        val layout = PaneLayout.of("a")
        assertSame(layout, layout.remove("a"))
    }

    @Test
    fun `removing an unknown pane changes nothing`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        assertSame(layout, layout.remove("zzz"))
    }

    @Test
    fun `moving a pane to another row keeps every pane exactly once`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.NewRow(1))
        val after = layout.move("b", PaneSlot.InRow(row = 1, column = 1))
        assertEquals("a|c,b", shapeOf(after))
        assertEquals(listOf("a", "c", "b"), after.paneIds)
        assertEquals(3, after.size)
    }

    @Test
    fun `moving the only pane of a row into another row drops the emptied row`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        val after = layout.move("b", PaneSlot.InRow(row = 0, column = 1))
        assertEquals("a,b", shapeOf(after))
        assertEquals(1, after.rows.size)
        assertEquals(1f, after.rows.single().weight)
    }

    @Test
    fun `moving a pane onto its own position changes nothing`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.InRow(row = 0, column = 1))
        assertEquals(shapeOf(layout), shapeOf(layout.move("b", PaneSlot.InRow(row = 0, column = 1))))
        // Dropping on the slot right before itself is the same position too.
        assertEquals(shapeOf(layout), shapeOf(layout.move("b", PaneSlot.InRow(row = 0, column = 2))))
    }

    @Test
    fun `moving into a new row below works after the source row collapses`() {
        // "a,b" -> drag b into a new row under it: the row index survives the collapse of nothing.
        val layout = PaneLayout.of("a").add("b", PaneSlot.InRow(row = 0, column = 1))
        val after = layout.move("b", PaneSlot.NewRow(1))
        assertEquals("a|b", shapeOf(after))
    }

    @Test
    fun `moving the last pane out of a row shifts the target row index`() {
        // "a|b|c|d": moving a (alone in row 0) collapses row 0, so every row below shifts up and
        // the drop must still land in c's row — not in d's. Four rows on purpose: with fewer, the
        // clamp on the last row would hide a missing shift.
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .add("c", PaneSlot.NewRow(2))
            .add("d", PaneSlot.NewRow(3))
        assertEquals("a|b|c|d", shapeOf(layout))
        assertEquals("b|c,a|d", shapeOf(layout.move("a", PaneSlot.InRow(row = 2, column = 1))))
    }

    @Test
    fun `moving the last pane of a row into a new row shifts the target row index`() {
        // "a|b|c": dropping a below b means "between b and c" after row 0 collapses.
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .add("c", PaneSlot.NewRow(2))
        assertEquals("b|a|c", shapeOf(layout.move("a", PaneSlot.NewRow(2))))
    }

    @Test
    fun `moving inside a row shifts the target column`() {
        // "a,b,c": dropping a between b and c must land between them, not past c. Three cells on
        // purpose: with two, the clamp on the last column would hide a missing shift.
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.InRow(row = 0, column = 2))
        assertEquals("b,a,c", shapeOf(layout.move("a", PaneSlot.InRow(row = 0, column = 2))))
    }

    @Test
    fun `moving an unknown pane changes nothing`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        assertSame(layout, layout.move("zzz", PaneSlot.InRow(row = 0, column = 0)))
    }

    @Test
    fun `dragging a row boundary moves height between the two rows`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        val after = layout.resizeRows(boundary = 0, delta = 0.2f)
        assertEquals(0.7f, after.rows[0].weight, 1e-4f)
        assertEquals(0.3f, after.rows[1].weight, 1e-4f)
        assertEquals(1f, weightsSum(after.rows.map { it.weight }), 1e-4f)
    }

    @Test
    fun `a row boundary cannot squeeze a pane below the minimum`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        val after = layout.resizeRows(boundary = 0, delta = 5f)
        assertEquals(1f - MIN_PANE_WEIGHT, after.rows[0].weight, 1e-4f)
        assertEquals(MIN_PANE_WEIGHT, after.rows[1].weight, 1e-4f)
    }

    @Test
    fun `resizing only touches the two panes around the boundary`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .add("c", PaneSlot.NewRow(2))
        val after = layout.resizeRows(boundary = 1, delta = 0.1f)
        assertEquals(layout.rows[0].weight, after.rows[0].weight, 1e-4f)
        assertNotEquals(layout.rows[1].weight, after.rows[1].weight)
    }

    @Test
    fun `dragging a column boundary moves width inside its row only`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.NewRow(1))
        val after = layout.resizeCells(row = 0, boundary = 0, delta = -0.2f)
        assertEquals(0.3f, after.rows[0].cells[0].weight, 1e-4f)
        assertEquals(0.7f, after.rows[0].cells[1].weight, 1e-4f)
        assertEquals(layout.rows[1], after.rows[1])
    }

    @Test
    fun `an out-of-range boundary is ignored`() {
        val layout = PaneLayout.of("a").add("b", PaneSlot.NewRow(1))
        assertSame(layout, layout.resizeRows(boundary = 5, delta = 0.1f))
        assertSame(layout, layout.resizeCells(row = 0, boundary = 0, delta = 0.1f)) // single cell: no boundary
        assertSame(layout, layout.resizeCells(row = 9, boundary = 0, delta = 0.1f))
    }

    @Test
    fun `adding into a hand-squeezed row keeps every pane above the minimum`() {
        // A row dragged to its minimum, then dropped into twice: scaling to make room would push
        // the squeezed pane under the floor and leave a sliver nobody can grab.
        val squeezed = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .resizeCells(row = 0, boundary = 0, delta = -5f)
        assertEquals(MIN_PANE_WEIGHT, squeezed.rows[0].cells[0].weight, 1e-4f)

        val after = squeezed
            .add("c", PaneSlot.InRow(row = 0, column = 2))
            .add("d", PaneSlot.InRow(row = 0, column = 3))

        assertEquals(1f, weightsSum(after.rows[0].cells.map { it.weight }), 1e-4f)
        after.rows[0].cells.forEach { assertTrue(it.weight >= MIN_PANE_WEIGHT - 1e-4f, "cell weight ${it.weight}") }
    }

    @Test
    fun `adding a row under a hand-squeezed one keeps every row above the minimum`() {
        val squeezed = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .resizeRows(boundary = 0, delta = -5f)

        val after = squeezed.add("c", PaneSlot.NewRow(2)).add("d", PaneSlot.NewRow(3))

        assertEquals(1f, weightsSum(after.rows.map { it.weight }), 1e-4f)
        after.rows.forEach { assertTrue(it.weight >= MIN_PANE_WEIGHT - 1e-4f, "row weight ${it.weight}") }
    }

    @Test
    fun `replacing a pane keeps its position and size`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .resizeCells(row = 0, boundary = 0, delta = 0.2f)
        val after = layout.replace("b", "b2")
        assertEquals("a,b2", shapeOf(after))
        assertEquals(layout.rows[0].cells[1].weight, after.rows[0].cells[1].weight)
    }

    @Test
    fun `replacing an unknown pane changes nothing`() {
        val layout = PaneLayout.of("a")
        assertSame(layout, layout.replace("zzz", "b"))
    }

    @Test
    fun `pane ids are listed in visual order`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.NewRow(1))
            .add("c", PaneSlot.InRow(row = 1, column = 1))
            .add("d", PaneSlot.InRow(row = 0, column = 0))
        assertEquals(listOf("d", "a", "b", "c"), layout.paneIds)
    }

    @Test
    fun `every operation keeps the weights normalized`() {
        val layout = PaneLayout.of("a")
            .add("b", PaneSlot.InRow(row = 0, column = 1))
            .add("c", PaneSlot.NewRow(1))
            .add("d", PaneSlot.InRow(row = 1, column = 0))
            .resizeRows(boundary = 0, delta = 0.13f)
            .resizeCells(row = 1, boundary = 0, delta = -0.07f)
            .move("d", PaneSlot.InRow(row = 0, column = 0))
            .remove("b")
        assertEquals(1f, weightsSum(layout.rows.map { it.weight }), 1e-4f)
        layout.rows.forEach { row ->
            assertEquals(1f, weightsSum(row.cells.map { it.weight }), 1e-4f)
            row.cells.forEach { assertTrue(it.weight >= MIN_PANE_WEIGHT - 1e-4f, "cell weight ${it.weight}") }
        }
    }
}
