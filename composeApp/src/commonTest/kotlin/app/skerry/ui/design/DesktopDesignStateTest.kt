package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDesignStateTest {

    @Test
    fun defaults_match_prototype() {
        val s = DesktopDesignState()
        assertEquals(DesktopView.Terminal, s.view)
        assertFalse(s.locked)
        assertFalse(s.modalOpen)
        assertFalse(s.settingsOpen)
        assertEquals(SettingsTab.AI, s.settingsTab)
        assertEquals("prod-web-01", s.selectedHost)
        assertEquals(0, s.activeTab)
        assertEquals(4, s.tabs.size)
        assertTrue(s.infoPanel)
        assertTrue(s.sanitize && s.preview && s.confirm)
    }

    @Test
    fun setView_switches_active_view() {
        val s = DesktopDesignState()
        s.showView(DesktopView.Vault)
        assertEquals(DesktopView.Vault, s.view)
    }

    @Test
    fun closeTab_active_picks_right_neighbor_then_clamps() {
        val s = DesktopDesignState() // 4 вкладок, активна 0
        s.setTab(3)                  // активна последняя
        s.closeTab(3)                // удалили последнюю — активная зажимается на новую последнюю (2)
        assertEquals(3, s.tabs.size)
        assertEquals(2, s.activeTab)
    }

    @Test
    fun closeTab_before_active_keeps_clamp_in_range() {
        val s = DesktopDesignState()
        s.setTab(1)
        s.closeTab(0)
        assertEquals(3, s.tabs.size)
        // activeTab=1 всё ещё в диапазоне [0..2]
        assertEquals(1, s.activeTab)
    }

    @Test
    fun closeTab_out_of_range_is_ignored() {
        val s = DesktopDesignState()
        s.closeTab(99)
        assertEquals(4, s.tabs.size)
    }

    @Test
    fun toggles_flip() {
        val s = DesktopDesignState()
        s.toggleSanitize(); assertFalse(s.sanitize)
        s.toggleSplit(); assertTrue(s.split)
        s.toggleInfo(); assertFalse(s.infoPanel)
        s.lock(); assertTrue(s.locked)
        s.unlock(); assertFalse(s.locked)
    }

    @Test
    fun runCmd_known_command_appends_cmd_and_output() {
        val s = DesktopDesignState()
        s.onCmd("whoami")
        s.runCmd()
        assertEquals(2, s.termLines.size)
        assertTrue(s.termLines[0].isCmd)
        assertEquals("whoami", s.termLines[0].text)
        assertEquals("root", s.termLines[1].text)
        assertEquals("", s.cmd)
    }

    @Test
    fun runCmd_unknown_command_reports_not_found() {
        val s = DesktopDesignState()
        s.onCmd("nope --x")
        s.runCmd()
        assertEquals("nope: command not found", s.termLines[1].text)
        assertEquals(D.sunset, s.termLines[1].color)
    }

    @Test
    fun runCmd_clear_empties_buffer() {
        val s = DesktopDesignState()
        s.onCmd("ls"); s.runCmd()
        s.onCmd("clear"); s.runCmd()
        assertTrue(s.termLines.isEmpty())
    }

    @Test
    fun settings_tab_navigation() {
        val s = DesktopDesignState()
        s.openSettings(); assertTrue(s.settingsOpen)
        s.showSettingsTab(SettingsTab.Security)
        assertEquals(SettingsTab.Security, s.settingsTab)
        s.closeSettings(); assertFalse(s.settingsOpen)
    }

    @Test
    fun modal_policy_selection() {
        val s = DesktopDesignState()
        s.openModal(); assertTrue(s.modalOpen)
        s.choosePolicy(AiPolicy.Permissive)
        assertEquals(AiPolicy.Permissive, s.modalPolicy)
        s.closeModal(); assertFalse(s.modalOpen)
    }
}
