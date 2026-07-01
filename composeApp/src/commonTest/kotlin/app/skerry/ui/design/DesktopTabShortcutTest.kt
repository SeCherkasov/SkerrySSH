package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Мок-путь табов (sessions=null): DesktopDesignState держит 4 демо-вкладки, activeTab стартует с 0. */
class DesktopTabShortcutTest {

    @Test
    fun `select tab by in-range index activates it`() {
        val state = DesktopDesignState()
        assertTrue(selectTabByIndex(2, state, sessions = null))
        assertEquals(2, state.activeTab)
    }

    @Test
    fun `select tab out of range leaves the active tab and reports unhandled`() {
        val state = DesktopDesignState()
        assertFalse(selectTabByIndex(9, state, sessions = null))
        assertEquals(0, state.activeTab)
    }

    @Test
    fun `next tab wraps past the last tab`() {
        val state = DesktopDesignState()
        // 4 вкладки: 0→1→2→3→0.
        repeat(3) { cycleTab(+1, state, sessions = null) }
        assertEquals(3, state.activeTab)
        assertTrue(cycleTab(+1, state, sessions = null))
        assertEquals(0, state.activeTab)
    }

    @Test
    fun `previous tab wraps before the first tab`() {
        val state = DesktopDesignState()
        assertTrue(cycleTab(-1, state, sessions = null))
        assertEquals(3, state.activeTab)
    }
}
