package app.skerry.ui.desktop

import app.skerry.ui.host.HostSection
import app.skerry.ui.session.SessionView
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pressing a rail section brings a collapsed hosts panel back — but only where the panel is drawn.
 * The file panel, a runbook run and the player fill the work area and show no catalog, so a press
 * there would change a preference the user cannot see and greet them with an open panel on the way
 * back to the terminal.
 */
class RailCatalogTest {

    @Test
    fun the_desktops_section_always_shows_its_catalog() {
        for (view in SessionView.entries) {
            assertTrue(showsCatalog(HostSection.RemoteDesktops, view), "$view")
        }
        assertTrue(showsCatalog(HostSection.RemoteDesktops, terminalView = null))
    }

    @Test
    fun the_terminal_and_the_monitor_keep_the_catalog_beside_them() {
        assertTrue(showsCatalog(HostSection.Terminal, SessionView.Terminal))
        assertTrue(showsCatalog(HostSection.Terminal, SessionView.Monitor))
        // No tab open at all: the section's own catalog is the whole screen.
        assertTrue(showsCatalog(HostSection.Terminal, terminalView = null))
    }

    @Test
    fun a_view_that_fills_the_work_area_shows_none() {
        assertFalse(showsCatalog(HostSection.Terminal, SessionView.Sftp))
        assertFalse(showsCatalog(HostSection.Terminal, SessionView.Runbook))
        assertFalse(showsCatalog(HostSection.Terminal, SessionView.Player))
    }
}
