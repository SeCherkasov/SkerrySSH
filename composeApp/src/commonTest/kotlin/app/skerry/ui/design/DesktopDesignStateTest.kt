package app.skerry.ui.design

import app.skerry.shared.host.Host
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TERMINAL_LETTER_SPACING_MIN
import app.skerry.ui.terminal.TERMINAL_LINE_HEIGHT_MAX
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
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
    fun host_search_query_starts_empty_and_updates() {
        val s = DesktopDesignState()
        assertEquals("", s.hostSearchQuery)
        s.onHostSearch("prod")
        assertEquals("prod", s.hostSearchQuery)
        s.onHostSearch("")
        assertEquals("", s.hostSearchQuery)
    }

    @Test
    fun request_and_dismiss_close_session_confirmation() {
        val s = DesktopDesignState()
        assertNull(s.pendingClose)
        s.requestCloseSession("sess-1")
        assertEquals(PendingClose.Session("sess-1"), s.pendingClose)
        s.dismissClose()
        assertNull(s.pendingClose)
    }

    @Test
    fun request_close_split_confirmation() {
        val s = DesktopDesignState()
        s.requestCloseSplit("sess-2")
        assertEquals(PendingClose.Split("sess-2"), s.pendingClose)
        s.dismissClose()
        assertNull(s.pendingClose)
    }

    @Test
    fun lock_clears_host_search_query() {
        val s = DesktopDesignState()
        s.onHostSearch("prod")
        s.lock()
        assertEquals("", s.hostSearchQuery)
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
        val s = DesktopDesignState() // 4 вкладки, активна 0
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

    // Правка / удаление существующего хоста

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

    // Персист видимости info-панели

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

    // Персист схлопнутых групп хостов

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

    // Персист недавних подключений (секция RECENT в сайдбаре)

    @Test
    fun recent_hosts_default_empty() {
        val s = DesktopDesignState()
        assertTrue(s.recentHostIds.isEmpty())
    }

    @Test
    fun recent_hosts_honour_initial_value() {
        val s = DesktopDesignState(initialRecentHostIds = listOf("h1", "h2"))
        assertEquals(listOf("h1", "h2"), s.recentHostIds)
    }

    @Test
    fun recordRecentHost_prepends_newest_and_dedupes_reporting_to_callback() {
        val seen = mutableListOf<List<String>>()
        val s = DesktopDesignState(onRecentHostIdsChange = { seen += it })
        s.recordRecentHost("a")
        s.recordRecentHost("b")
        s.recordRecentHost("a") // повторный коннект двигает «a» в начало, без дубля
        assertEquals(listOf("a", "b"), s.recentHostIds)
        assertEquals(listOf(listOf("a"), listOf("b", "a"), listOf("a", "b")), seen)
    }

    @Test
    fun recordRecentHost_caps_at_eight_keeping_most_recent() {
        val s = DesktopDesignState()
        (1..9).forEach { s.recordRecentHost("h$it") }
        assertEquals(8, s.recentHostIds.size)
        assertEquals("h9", s.recentHostIds.first())
        assertFalse("h1" in s.recentHostIds) // самый старый вытеснен
    }

    @Test
    fun recordRecentHost_noop_when_already_at_front() {
        val seen = mutableListOf<List<String>>()
        val s = DesktopDesignState(onRecentHostIdsChange = { seen += it })
        s.recordRecentHost("a")
        s.recordRecentHost("a") // уже первый — ни записи, ни колбэка
        assertEquals(listOf("a"), s.recentHostIds)
        assertEquals(listOf(listOf("a")), seen)
    }

    @Test
    fun recordRecentHost_ignores_blank_id() {
        val seen = mutableListOf<List<String>>()
        val s = DesktopDesignState(onRecentHostIdsChange = { seen += it })
        s.recordRecentHost("")
        assertTrue(s.recentHostIds.isEmpty())
        assertTrue(seen.isEmpty())
    }

    // Пользовательские (пустые) группы хостов

    @Test
    fun custom_groups_default_empty() {
        assertTrue(DesktopDesignState().customGroups.isEmpty())
    }

    @Test
    fun addCustomGroup_appends_trimmed_and_reports() {
        val seen = mutableListOf<List<String>>()
        val s = DesktopDesignState(onCustomGroupsChange = { seen += it })
        s.addCustomGroup("  Prod  ")
        s.addCustomGroup("Dev")
        assertEquals(listOf("Prod", "Dev"), s.customGroups)
        assertEquals(listOf(listOf("Prod"), listOf("Prod", "Dev")), seen)
    }

    @Test
    fun addCustomGroup_ignores_blank_and_exact_duplicate_but_allows_other_case() {
        val seen = mutableListOf<List<String>>()
        val s = DesktopDesignState(onCustomGroupsChange = { seen += it })
        s.addCustomGroup("Prod")
        s.addCustomGroup("   ")
        s.addCustomGroup("Prod") // точный дубль — игнор
        // Иной регистр — это иная группа (Host.group/папки сопоставляются по регистру), поэтому добавляется.
        s.addCustomGroup("prod")
        assertEquals(listOf("Prod", "prod"), s.customGroups)
        assertEquals(listOf(listOf("Prod"), listOf("Prod", "prod")), seen)
    }

    @Test
    fun renameGroupName_updates_custom_and_collapsed() {
        val groups = mutableListOf<List<String>>()
        val collapsed = mutableListOf<Set<String>>()
        val s = DesktopDesignState(
            initialCollapsedGroups = setOf("Prod"),
            onCollapsedGroupsChange = { collapsed += it },
            initialCustomGroups = listOf("Prod"),
            onCustomGroupsChange = { groups += it },
        )
        s.renameGroupName("Prod", "Production")
        assertEquals(listOf("Production"), s.customGroups)
        assertTrue(s.isGroupCollapsed("Production"))
        assertFalse(s.isGroupCollapsed("Prod"))
        assertEquals(listOf(listOf("Production")), groups)
        assertEquals(listOf(setOf("Production")), collapsed)
    }

    @Test
    fun renameGroupName_ignores_blank_or_unchanged() {
        val s = DesktopDesignState(initialCustomGroups = listOf("Prod"))
        s.renameGroupName("Prod", "  ")
        s.renameGroupName("Prod", "Prod") // точно то же имя — no-op
        assertEquals(listOf("Prod"), s.customGroups)
    }

    @Test
    fun renameGroupName_applies_case_only_change() {
        val s = DesktopDesignState(initialCustomGroups = listOf("Prod"))
        s.renameGroupName("Prod", "prod") // правка только регистра — реальное переименование
        assertEquals(listOf("prod"), s.customGroups)
    }

    @Test
    fun removeCustomGroup_drops_from_custom_and_collapsed() {
        val s = DesktopDesignState(
            initialCollapsedGroups = setOf("Prod"),
            initialCustomGroups = listOf("Prod", "Dev"),
        )
        s.removeCustomGroup("Prod")
        assertEquals(listOf("Dev"), s.customGroups)
        assertFalse(s.isGroupCollapsed("Prod"))
    }

    // Шрифт и кегль терминала (Appearance → Font / Font size)

    @Test
    fun terminal_font_defaults_to_hack_13px() {
        val s = DesktopDesignState()
        assertEquals(TerminalFont.Hack, s.terminalFont)
        assertEquals(DEFAULT_TERMINAL_FONT_SIZE, s.terminalFontSize)
    }

    @Test
    fun terminal_font_honours_initial_values() {
        val s = DesktopDesignState(initialTerminalFont = TerminalFont.JetBrainsMono, initialTerminalFontSize = 16)
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(16, s.terminalFontSize)
    }

    @Test
    fun setTerminalFont_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalFont>()
        val s = DesktopDesignState(onTerminalFontChange = { seen += it })
        s.chooseTerminalFont(TerminalFont.JetBrainsMono)
        s.chooseTerminalFont(TerminalFont.JetBrainsMono) // повтор того же — no-op
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(listOf(TerminalFont.JetBrainsMono), seen)
    }

    @Test
    fun setTerminalTheme_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalTheme>()
        val s = DesktopDesignState(onTerminalThemeChange = { seen += it })
        assertEquals(TerminalThemes.DEFAULT, s.terminalTheme) // дефолт — Night Sea
        s.chooseTerminalTheme(TerminalThemes.GruvboxDark)
        s.chooseTerminalTheme(TerminalThemes.GruvboxDark) // повтор той же — no-op
        assertEquals(TerminalThemes.GruvboxDark, s.terminalTheme)
        assertEquals(listOf(TerminalThemes.GruvboxDark), seen)
    }

    @Test
    fun setTerminalFontSize_updates_and_reports_skipping_repeat_and_out_of_range() {
        val seen = mutableListOf<Int>()
        val s = DesktopDesignState(onTerminalFontSizeChange = { seen += it })
        s.chooseTerminalFontSize(16)
        s.chooseTerminalFontSize(16)   // повтор — no-op
        s.chooseTerminalFontSize(99)   // вне TERMINAL_FONT_SIZE_RANGE — no-op
        s.chooseTerminalFontSize(11)
        assertEquals(11, s.terminalFontSize)
        assertEquals(listOf(16, 11), seen)
    }

    @Test
    fun terminalFontSize_accepts_wide_range() {
        val s = DesktopDesignState()
        s.chooseTerminalFontSize(TERMINAL_FONT_SIZE_MIN)
        assertEquals(TERMINAL_FONT_SIZE_MIN, s.terminalFontSize)
        s.chooseTerminalFontSize(TERMINAL_FONT_SIZE_MAX)
        assertEquals(TERMINAL_FONT_SIZE_MAX, s.terminalFontSize)
    }

    @Test
    fun setTerminalLineHeight_clamps_rounds_and_reports_skipping_repeat() {
        val seen = mutableListOf<Float>()
        val s = DesktopDesignState(onTerminalLineHeightChange = { seen += it })
        assertEquals(DEFAULT_TERMINAL_LINE_HEIGHT, s.terminalLineHeight)
        s.chooseTerminalLineHeight(1.5f)
        s.chooseTerminalLineHeight(1.5f)   // повтор — no-op
        s.chooseTerminalLineHeight(5f)     // вне диапазона → clamp до MAX
        assertEquals(TERMINAL_LINE_HEIGHT_MAX, s.terminalLineHeight)
        assertEquals(listOf(1.5f, TERMINAL_LINE_HEIGHT_MAX), seen)
    }

    @Test
    fun setTerminalLetterSpacing_clamps_rounds_and_reports_skipping_repeat() {
        val seen = mutableListOf<Float>()
        val s = DesktopDesignState(onTerminalLetterSpacingChange = { seen += it })
        assertEquals(DEFAULT_TERMINAL_LETTER_SPACING, s.terminalLetterSpacing)
        s.chooseTerminalLetterSpacing(1f)
        s.chooseTerminalLetterSpacing(1f)  // повтор — no-op
        s.chooseTerminalLetterSpacing(-9f) // вне диапазона → clamp до MIN
        assertEquals(TERMINAL_LETTER_SPACING_MIN, s.terminalLetterSpacing)
        assertEquals(listOf(1f, TERMINAL_LETTER_SPACING_MIN), seen)
    }

    @Test
    fun terminal_behaviour_honours_initial_values() {
        val s = DesktopDesignState(
            initialTerminalScrollback = 50_000,
            initialTerminalCursorStyle = TerminalCursorStyle.BarSteady,
            initialShowTerminalTitleOnTabs = true,
        )
        assertEquals(50_000, s.terminalScrollback)
        assertEquals(TerminalCursorStyle.BarSteady, s.terminalCursorStyle)
        assertEquals(true, s.showTerminalTitleOnTabs)
    }

    @Test
    fun setTerminalScrollback_updates_and_reports_skipping_repeat_and_out_of_range() {
        val seen = mutableListOf<Int>()
        val s = DesktopDesignState(onTerminalScrollbackChange = { seen += it })
        s.chooseTerminalScrollback(5_000)
        s.chooseTerminalScrollback(5_000)   // повтор — no-op
        s.chooseTerminalScrollback(1_234)   // вне TERMINAL_SCROLLBACK_OPTIONS — no-op
        s.chooseTerminalScrollback(1_000)
        assertEquals(1_000, s.terminalScrollback)
        assertEquals(listOf(5_000, 1_000), seen)
    }

    @Test
    fun setTerminalCursorStyle_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalCursorStyle>()
        val s = DesktopDesignState(onTerminalCursorStyleChange = { seen += it })
        s.chooseTerminalCursorStyle(TerminalCursorStyle.UnderlineBlink)
        s.chooseTerminalCursorStyle(TerminalCursorStyle.UnderlineBlink) // повтор — no-op
        assertEquals(TerminalCursorStyle.UnderlineBlink, s.terminalCursorStyle)
        assertEquals(listOf(TerminalCursorStyle.UnderlineBlink), seen)
    }

    @Test
    fun toggleShowTerminalTitleOnTabs_flips_and_reports_each_change() {
        val seen = mutableListOf<Boolean>()
        val s = DesktopDesignState(onShowTerminalTitleOnTabsChange = { seen += it })
        assertEquals(false, s.showTerminalTitleOnTabs)
        s.toggleShowTerminalTitleOnTabs()
        s.toggleShowTerminalTitleOnTabs()
        assertEquals(false, s.showTerminalTitleOnTabs)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun group_dialog_open_and_dismiss() {
        val s = DesktopDesignState()
        assertEquals(null, s.groupDialog)
        s.openCreateGroup()
        assertEquals(GroupDialog.Create, s.groupDialog)
        s.openRenameGroup("Prod")
        assertEquals(GroupDialog.Rename("Prod"), s.groupDialog)
        s.dismissGroupDialog()
        assertEquals(null, s.groupDialog)
    }
}
