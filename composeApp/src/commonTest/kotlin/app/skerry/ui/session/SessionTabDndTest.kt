package app.skerry.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTabDndTest {

    // X centers of the three remaining tabs (list order).
    private val centers = listOf(50f, 150f, 250f)

    @Test
    fun `pointer before all tabs inserts at front`() {
        assertEquals(0, tabInsertIndex(centers, pointerX = 10f))
    }

    @Test
    fun `pointer past all tabs inserts at end`() {
        assertEquals(3, tabInsertIndex(centers, pointerX = 999f))
    }

    @Test
    fun `pointer between tabs inserts at that slot`() {
        assertEquals(1, tabInsertIndex(centers, pointerX = 100f)) // between 1st and 2nd
        assertEquals(2, tabInsertIndex(centers, pointerX = 200f)) // between 2nd and 3rd
    }

    @Test
    fun `empty other-centers inserts at front`() {
        assertEquals(0, tabInsertIndex(emptyList(), pointerX = 123f))
    }
}
