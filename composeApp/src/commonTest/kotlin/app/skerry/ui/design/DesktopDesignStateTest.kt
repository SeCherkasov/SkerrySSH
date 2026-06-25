package app.skerry.ui.design

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun showView_session_level_sets_view_and_clears_overlay() {
        val s = DesktopDesignState()
        s.showView(DesktopView.Vault)        // сначала откроем app-overlay
        s.showView(DesktopView.Sftp)         // session-level — должен сбросить overlay
        assertEquals(DesktopView.Sftp, s.view)
        assertNull(s.appOverlay)
    }

    @Test
    fun showView_app_level_sets_overlay_keeping_session_view() {
        val s = DesktopDesignState()
        s.showView(DesktopView.Sftp)         // session-вью = Sftp
        s.showView(DesktopView.Vault)        // app-level → overlay
        assertEquals(DesktopView.Vault, s.appOverlay)
        assertEquals(DesktopView.Sftp, s.view) // session-вью сохранилась под оверлеем
    }

    @Test
    fun default_has_no_app_overlay() {
        assertNull(DesktopDesignState().appOverlay)
    }

    @Test
    fun desktopView_isAppLevel_split() {
        assertFalse(DesktopView.Terminal.isAppLevel)
        assertFalse(DesktopView.Sftp.isAppLevel)
        assertTrue(DesktopView.Ports.isAppLevel) // Tunnels — глобальный раздел (привычная модель SSH-клиентов)
        assertTrue(DesktopView.Snippets.isAppLevel)
        assertTrue(DesktopView.Vault.isAppLevel)
        assertTrue(DesktopView.Known.isAppLevel)
        assertTrue(DesktopView.Teams.isAppLevel)
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

    // --- Правка / удаление существующего хоста ---

    private val sampleHost = Host(id = "h1", label = "box", address = "a", port = 22, username = "u")

    @Test
    fun openEditModal_opens_with_target_and_close_clears_it() {
        val s = DesktopDesignState()
        s.openEditModal(sampleHost)
        assertTrue(s.modalOpen)
        assertEquals(sampleHost, s.editingHost)
        s.closeModal()
        assertFalse(s.modalOpen)
        assertNull(s.editingHost)
    }

    @Test
    fun openModal_resets_edit_target_for_new_connection() {
        val s = DesktopDesignState()
        s.openEditModal(sampleHost)
        s.openModal() // «New connection» поверх правки — должен сбросить цель
        assertTrue(s.modalOpen)
        assertNull(s.editingHost)
    }

    @Test
    fun delete_host_request_then_dismiss() {
        val s = DesktopDesignState()
        s.requestDeleteHost(sampleHost)
        assertEquals(sampleHost, s.pendingDeleteHost)
        s.dismissDeleteHost()
        assertNull(s.pendingDeleteHost)
    }

    // --- Персист видимости info-панели ---

    @Test
    fun info_panel_honours_initial_value() {
        assertFalse(DesktopDesignState(initialInfoPanel = false).infoPanel)
        assertTrue(DesktopDesignState(initialInfoPanel = true).infoPanel)
    }

    @Test
    fun toggleInfo_reports_new_value_to_callback() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopDesignState(initialInfoPanel = true, onInfoPanelChange = { seen += it })
        s.toggleInfo() // true → false
        s.toggleInfo() // false → true
        assertEquals(listOf(false, true), seen)
        assertTrue(s.infoPanel)
    }

    // --- Персист схлопнутых групп хостов ---

    @Test
    fun collapsed_groups_default_empty() {
        val s = DesktopDesignState()
        assertTrue(s.collapsedGroups.isEmpty())
        assertFalse(s.isGroupCollapsed("Uran SecureNet"))
    }

    @Test
    fun collapsed_groups_honour_initial_value() {
        val s = DesktopDesignState(initialCollapsedGroups = setOf("Uran SecureNet"))
        assertTrue(s.isGroupCollapsed("Uran SecureNet"))
        assertFalse(s.isGroupCollapsed("Other"))
    }

    @Test
    fun toggleGroupCollapsed_flips_membership_and_reports_to_callback() {
        val seen = mutableListOf<Set<String>>()
        val s = DesktopDesignState(onCollapsedGroupsChange = { seen += it })
        s.toggleGroupCollapsed("A") // добавили
        assertTrue(s.isGroupCollapsed("A"))
        s.toggleGroupCollapsed("B") // добавили вторую
        s.toggleGroupCollapsed("A") // убрали первую
        assertFalse(s.isGroupCollapsed("A"))
        assertTrue(s.isGroupCollapsed("B"))
        assertEquals(listOf(setOf("A"), setOf("A", "B"), setOf("B")), seen)
    }
}
