package app.skerry.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTabDndTest {

    // Центры трёх оставшихся вкладок по X (порядок списка).
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
        assertEquals(1, tabInsertIndex(centers, pointerX = 100f)) // между 1-й и 2-й
        assertEquals(2, tabInsertIndex(centers, pointerX = 200f)) // между 2-й и 3-й
    }

    @Test
    fun `empty other-centers inserts at front`() {
        assertEquals(0, tabInsertIndex(emptyList(), pointerX = 123f))
    }
}
