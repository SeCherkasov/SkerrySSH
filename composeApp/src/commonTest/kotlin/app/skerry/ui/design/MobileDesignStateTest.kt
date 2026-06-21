package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileDesignStateTest {

    @Test
    fun defaults_open_on_hosts_with_tabs_visible() {
        val s = MobileDesignState()
        assertEquals(MobileTab.Hosts, s.tab)
        assertNull(s.route)
        assertFalse(s.sheetNewConn)
        assertTrue(s.showTabs)
    }

    @Test
    fun five_tabs_in_template_order() {
        // Нижняя навигация макета `Skerry Mobile.html`: ровно 5 табов в этом порядке.
        assertEquals(
            listOf(
                MobileTab.Hosts,
                MobileTab.Files,
                MobileTab.Snippets,
                MobileTab.Vault,
                MobileTab.More,
            ),
            MobileTab.entries.toList(),
        )
    }

    @Test
    fun select_switches_tab() {
        val s = MobileDesignState()
        s.select(MobileTab.Vault)
        assertEquals(MobileTab.Vault, s.tab)
    }

    @Test
    fun push_route_hides_tabs_pop_restores_them() {
        val s = MobileDesignState()
        s.push(MobileRoute.Terminal)
        assertEquals(MobileRoute.Terminal, s.route)
        assertFalse(s.showTabs) // push-экраны полноэкранные, без таб-бара
        s.pop()
        assertNull(s.route)
        assertTrue(s.showTabs)
    }

    @Test
    fun pop_returns_to_current_tab_not_hosts() {
        // Из таба More открыли под-экран (Ports) → pop возвращает на More, а не на Hosts.
        val s = MobileDesignState()
        s.select(MobileTab.More)
        s.push(MobileRoute.Ports)
        s.pop()
        assertEquals(MobileTab.More, s.tab)
        assertNull(s.route)
    }

    @Test
    fun select_tab_clears_any_open_route() {
        val s = MobileDesignState()
        s.push(MobileRoute.HostDetail)
        s.select(MobileTab.Files)
        assertEquals(MobileTab.Files, s.tab)
        assertNull(s.route)
    }

    @Test
    fun open_host_pushes_detail_with_selected_id() {
        val s = MobileDesignState()
        s.openHost("host-42")
        assertEquals(MobileRoute.HostDetail, s.route)
        assertEquals("host-42", s.selectedHostId)
        assertFalse(s.showTabs)
    }

    @Test
    fun pop_and_select_clear_selected_host_id() {
        val s = MobileDesignState()
        s.openHost("host-42")
        s.pop()
        assertNull(s.selectedHostId) // иначе 2B прочёл бы устаревший id

        s.openHost("host-7")
        s.select(MobileTab.Files)
        assertNull(s.selectedHostId)
    }

    @Test
    fun new_connection_sheet_opens_and_closes() {
        val s = MobileDesignState()
        s.openNewConn()
        assertTrue(s.sheetNewConn)
        s.closeSheet()
        assertFalse(s.sheetNewConn)
    }
}
