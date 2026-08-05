package app.skerry.ui.mobile

import app.skerry.ui.app.MobileRoute
import app.skerry.ui.session.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rows of the Sessions root tab: what it lists, what a tap opens, and what it leaves out. */
class MobileSessionsTest {

    private fun info(
        id: String,
        title: String = "prod-web-01",
        subtitle: String = "root@10.0.0.1:22",
        isVnc: Boolean = false,
        isPlayer: Boolean = false,
        isBlank: Boolean = false,
        status: SessionStatus = SessionStatus.Live,
    ) = MobileSessionInfo(id, title, subtitle, isVnc, isPlayer, isBlank, status)

    @Test
    fun a_terminal_session_opens_the_terminal_screen() {
        val rows = mobileSessionRows(listOf(info("t1")))
        assertEquals(1, rows.size)
        assertEquals("t1", rows[0].tabId)
        assertEquals("prod-web-01", rows[0].title)
        assertEquals("root@10.0.0.1:22", rows[0].subtitle)
        assertEquals(MobileRoute.Terminal, rows[0].route)
        assertEquals(SessionStatus.Live, rows[0].status)
    }

    @Test
    fun a_remote_desktop_session_opens_the_framebuffer_screen() {
        val rows = mobileSessionRows(listOf(info("d1", isVnc = true)))
        assertEquals(MobileRoute.Vnc, rows[0].route)
        // The list mixes both kinds and the icon is the only place that says which.
        assertEquals("desktop_windows", rows[0].icon)
    }

    @Test
    fun a_recording_being_replayed_opens_the_terminal_screen() {
        // A player holds no connection but replays into the same work area.
        val rows = mobileSessionRows(listOf(info("p1", isPlayer = true)))
        assertEquals(MobileRoute.Terminal, rows[0].route)
        // ...and must not read as a live shell: same screen, different thing entirely.
        assertEquals("play_circle", rows[0].icon)
    }

    @Test
    fun a_shell_carries_the_terminal_icon() {
        assertEquals("terminal", mobileSessionRows(listOf(info("t1")))[0].icon)
    }

    @Test
    fun blank_tabs_are_not_listed() {
        // A tab with nothing connected has no session to return to: listing it would offer a row
        // that opens an empty terminal screen.
        val rows = mobileSessionRows(listOf(info("b1", isBlank = true), info("t1")))
        assertEquals(listOf("t1"), rows.map { it.tabId })
    }

    @Test
    fun order_follows_the_open_order() {
        val rows = mobileSessionRows(listOf(info("t1"), info("d1", isVnc = true), info("t2")))
        assertEquals(listOf("t1", "d1", "t2"), rows.map { it.tabId })
    }

    @Test
    fun nothing_open_means_no_rows() {
        assertTrue(mobileSessionRows(emptyList()).isEmpty())
        // A shell that is only ever a blank tab still leaves the screen on its empty state.
        assertTrue(mobileSessionRows(listOf(info("b1", isBlank = true))).isEmpty())
    }

    @Test
    fun a_failed_session_keeps_its_row_with_its_status() {
        // A dropped session is exactly what the user comes to this screen to find; hiding it would
        // leave the failure invisible until the host row is tapped again.
        val rows = mobileSessionRows(listOf(info("t1", status = SessionStatus.Failed)))
        assertEquals(SessionStatus.Failed, rows[0].status)
    }

    // Session strip on the terminal screen

    @Test
    fun the_strip_lists_shells_and_marks_the_one_on_screen() {
        val chips = mobileTerminalStrip(listOf(info("t1"), info("t2", title = "db-master")), activeId = "t2")
        assertEquals(listOf("t1", "t2"), chips.map { it.tabId })
        assertEquals(listOf("prod-web-01", "db-master"), chips.map { it.label })
        assertEquals(listOf(false, true), chips.map { it.active })
    }

    @Test
    fun a_remote_desktop_is_not_a_terminal_strip_chip() {
        // Tapping it would swap the terminal for a framebuffer without saying so.
        val chips = mobileTerminalStrip(listOf(info("t1"), info("d1", isVnc = true)), activeId = "t1")
        assertEquals(listOf("t1"), chips.map { it.tabId })
    }

    @Test
    fun an_unknown_active_id_marks_nothing_instead_of_guessing() {
        // The active tab is a remote desktop, or was just closed: no chip claims to be on screen.
        val chips = mobileTerminalStrip(listOf(info("t1"), info("t2")), activeId = "gone")
        assertTrue(chips.none { it.active })
    }

    @Test
    fun a_blank_tab_is_not_a_strip_chip() {
        // Nothing is connected in it, so its chip would carry an empty label and switch to an empty
        // screen. "+" is the way to a new session, not a placeholder tab.
        val chips = mobileTerminalStrip(listOf(info("b1", isBlank = true), info("t1")), activeId = "t1")
        assertEquals(listOf("t1"), chips.map { it.tabId })
    }

    @Test
    fun the_strip_keeps_a_failed_shell() {
        // Its chip is how the user gets back to the screen that says what went wrong.
        val chips = mobileTerminalStrip(listOf(info("t1", status = SessionStatus.Failed)), activeId = "t1")
        assertEquals(SessionStatus.Failed, chips.single().status)
    }
}
