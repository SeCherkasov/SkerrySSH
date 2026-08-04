package app.skerry.ui.app

import app.skerry.shared.host.Host
import app.skerry.ui.host.HostSection
import app.skerry.ui.settings.SETTINGS_NAV
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
import app.skerry.ui.theme.ThemeMode

class DesktopDesignStateTest {

    @Test
    fun defaults_match_reference() {
        val s = DesktopDesignState()
        assertEquals(DesktopView.Terminal, s.view)
        assertFalse(s.locked)
        assertFalse(s.modalOpen)
        assertFalse(s.settingsOpen)
        assertEquals(SettingsTab.Sync, s.settingsTab)
        assertEquals("prod-web-01", s.selectedHost)
        assertEquals(0, s.activeTab)
        assertEquals(4, s.tabs.size)
        assertTrue(s.sanitize && s.preview && s.confirm)
    }

    @Test
    fun open_settings_resets_to_the_first_nav_tab() {
        val s = DesktopDesignState()
        s.openSettings()
        s.showSettingsTab(SettingsTab.About)
        s.closeSettings()
        s.openSettings()
        assertEquals(SETTINGS_NAV.first().tab, s.settingsTab)
    }

    @Test
    fun work_area_starts_on_the_terminal_section() {
        assertEquals(HostSection.Terminal, DesktopDesignState().section)
    }

    @Test
    fun showSection_switches_the_work_area_and_clears_the_overlay() {
        val s = DesktopDesignState()
        s.showView(DesktopView.Vault)                 // an app-level section is open over the tabs
        s.showSection(HostSection.RemoteDesktops)
        assertEquals(HostSection.RemoteDesktops, s.section)
        assertNull(s.appOverlay)                      // the rail click must reveal the work area
    }

    @Test
    fun showSection_keeps_the_session_sub_view() {
        val s = DesktopDesignState()
        s.showView(DesktopView.Sftp)
        s.showSection(HostSection.RemoteDesktops)
        s.showSection(HostSection.Terminal)
        // Returning to the terminal lands back on the sub-view that was open, not a reset to Terminal.
        assertEquals(DesktopView.Sftp, s.view)
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
        s.requestClosePane("sess-2", "pane-9")
        assertEquals(PendingClose.Pane("sess-2", "pane-9"), s.pendingClose)
        s.dismissClose()
        assertNull(s.pendingClose)
    }

    @Test
    fun request_and_dismiss_pane_connect_confirmation() {
        val s = DesktopDesignState()
        assertNull(s.pendingPaneConnect)
        s.requestPaneConnect("sess-2", "pane-9", sampleHost)
        assertEquals(PendingPaneConnect("sess-2", "pane-9", sampleHost), s.pendingPaneConnect)
        s.dismissPaneConnect()
        assertNull(s.pendingPaneConnect)
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
        s.showView(DesktopView.Vault)        // open an app-overlay first
        s.showView(DesktopView.Sftp)         // session-level view must clear the overlay
        assertEquals(DesktopView.Sftp, s.view)
        assertNull(s.appOverlay)
    }

    @Test
    fun showView_app_level_sets_overlay_keeping_session_view() {
        val s = DesktopDesignState()
        s.showView(DesktopView.Sftp)         // session view = Sftp
        s.showView(DesktopView.Vault)        // app-level → overlay
        assertEquals(DesktopView.Vault, s.appOverlay)
        assertEquals(DesktopView.Sftp, s.view) // session view is preserved under the overlay
    }

    @Test
    fun default_has_no_app_overlay() {
        assertNull(DesktopDesignState().appOverlay)
    }

    @Test
    fun desktopView_isAppLevel_split() {
        assertFalse(DesktopView.Terminal.isAppLevel)
        assertFalse(DesktopView.Sftp.isAppLevel)
        assertTrue(DesktopView.Ports.isAppLevel) // Tunnels is a global section
        assertTrue(DesktopView.Snippets.isAppLevel)
        assertTrue(DesktopView.Vault.isAppLevel)
        assertTrue(DesktopView.Known.isAppLevel)
        assertTrue(DesktopView.Teams.isAppLevel)
    }

    @Test
    fun closeTab_active_picks_right_neighbor_then_clamps() {
        val s = DesktopDesignState() // 4 tabs, tab 0 active
        s.setTab(3)                  // last tab active
        s.closeTab(3)                // closing the last tab clamps active to the new last tab (2)
        assertEquals(3, s.tabs.size)
        assertEquals(2, s.activeTab)
    }

    @Test
    fun closeTab_before_active_keeps_clamp_in_range() {
        val s = DesktopDesignState()
        s.setTab(1)
        s.closeTab(0)
        assertEquals(3, s.tabs.size)
        // activeTab=1 is still within [0..2]
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
        // The state layer stores only the semantic flag; the renderer maps it to the theme's sunset.
        assertTrue(s.termLines[1].error)
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

    // Editing/deleting an existing host

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
        s.openModal() // "New connection" over an edit must reset the edit target
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

    // Persisting info-panel visibility

    // Persisting collapsed host groups

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
        s.toggleGroupCollapsed("A") // added
        assertTrue(s.isGroupCollapsed("A"))
        s.toggleGroupCollapsed("B") // added a second
        s.toggleGroupCollapsed("A") // removed the first
        assertFalse(s.isGroupCollapsed("A"))
        assertTrue(s.isGroupCollapsed("B"))
        assertEquals(listOf(setOf("A"), setOf("A", "B"), setOf("B")), seen)
    }

    // Persisting recent connections (RECENT section in the sidebar)

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
        s.recordRecentHost("a") // reconnecting moves "a" to the front without duplicating it
        assertEquals(listOf("a", "b"), s.recentHostIds)
        assertEquals(listOf(listOf("a"), listOf("b", "a"), listOf("a", "b")), seen)
    }

    @Test
    fun recordRecentHost_caps_at_eight_keeping_most_recent() {
        val s = DesktopDesignState()
        (1..9).forEach { s.recordRecentHost("h$it") }
        assertEquals(8, s.recentHostIds.size)
        assertEquals("h9", s.recentHostIds.first())
        assertFalse("h1" in s.recentHostIds) // oldest entry evicted
    }

    @Test
    fun recordRecentHost_noop_when_already_at_front() {
        val seen = mutableListOf<List<String>>()
        val s = DesktopDesignState(onRecentHostIdsChange = { seen += it })
        s.recordRecentHost("a")
        s.recordRecentHost("a") // already first — no write, no callback
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

    // RECENT section visibility and size (Settings → Appearance → Interface)

    // Custom (empty) host groups

    @Test
    fun custom_groups_default_empty() {
        assertTrue(DesktopDesignState().customGroups.isEmpty())
    }

    @Test
    fun addCustomGroup_appends_trimmed_and_reports() {
        val seen = mutableListOf<List<CustomGroup>>()
        val s = DesktopDesignState(onCustomGroupsChange = { seen += it })
        s.addCustomGroup("  Prod  ", HostSection.Terminal)
        s.addCustomGroup("Dev", HostSection.Terminal)
        assertEquals(listOf("Prod", "Dev"), s.customGroupsIn(HostSection.Terminal))
        assertEquals(2, seen.size)
        assertEquals(listOf("Prod", "Dev"), seen.last().map { it.name })
    }

    @Test
    fun a_new_group_belongs_to_the_section_it_was_created_in() {
        // A folder created in the remote-desktop sidebar must not show up among the shells (and back).
        val s = DesktopDesignState()
        s.addCustomGroup("Screens", HostSection.RemoteDesktops)
        assertEquals(listOf("Screens"), s.customGroupsIn(HostSection.RemoteDesktops))
        assertEquals(emptyList(), s.customGroupsIn(HostSection.Terminal))
    }

    @Test
    fun addCustomGroup_ignores_blank_and_exact_duplicate_but_allows_other_case() {
        val seen = mutableListOf<List<CustomGroup>>()
        val s = DesktopDesignState(onCustomGroupsChange = { seen += it })
        s.addCustomGroup("Prod", HostSection.Terminal)
        s.addCustomGroup("   ", HostSection.Terminal)
        s.addCustomGroup("Prod", HostSection.Terminal) // exact duplicate — ignored
        // Different case is a different group (Host.group/folders match case-sensitively), so it's added.
        s.addCustomGroup("prod", HostSection.Terminal)
        assertEquals(listOf("Prod", "prod"), s.customGroupsIn(HostSection.Terminal))
        assertEquals(2, seen.size)
    }

    @Test
    fun renameGroupName_updates_custom_and_collapsed() {
        val groups = mutableListOf<List<CustomGroup>>()
        val collapsed = mutableListOf<Set<String>>()
        val s = DesktopDesignState(
            initialCollapsedGroups = setOf("Prod"),
            onCollapsedGroupsChange = { collapsed += it },
            initialCustomGroups = listOf(CustomGroup("Prod", HostSection.Terminal)),
            onCustomGroupsChange = { groups += it },
        )
        s.renameGroupName("Prod", "Production")
        assertEquals(listOf("Production"), s.customGroupsIn(HostSection.Terminal))
        assertTrue(s.isGroupCollapsed("Production"))
        assertFalse(s.isGroupCollapsed("Prod"))
        assertEquals(listOf(listOf(CustomGroup("Production", HostSection.Terminal))), groups)
        assertEquals(listOf(setOf("Production")), collapsed)
    }

    @Test
    fun renameGroupName_ignores_blank_or_unchanged() {
        val s = DesktopDesignState(initialCustomGroups = listOf(CustomGroup("Prod", HostSection.Terminal)))
        s.renameGroupName("Prod", "  ")
        s.renameGroupName("Prod", "Prod") // exact same name — no-op
        assertEquals(listOf("Prod"), s.customGroupsIn(HostSection.Terminal))
    }

    @Test
    fun renameGroupName_applies_case_only_change() {
        val s = DesktopDesignState(initialCustomGroups = listOf(CustomGroup("Prod", HostSection.Terminal)))
        s.renameGroupName("Prod", "prod") // case-only edit is a real rename
        assertEquals(listOf("prod"), s.customGroupsIn(HostSection.Terminal))
    }

    @Test
    fun removeCustomGroup_drops_from_custom_and_collapsed() {
        val s = DesktopDesignState(
            initialCollapsedGroups = setOf("Prod"),
            initialCustomGroups = listOf(
                CustomGroup("Prod", HostSection.Terminal),
                CustomGroup("Dev", HostSection.Terminal),
            ),
        )
        s.removeCustomGroup("Prod")
        assertEquals(listOf("Dev"), s.customGroupsIn(HostSection.Terminal))
        assertFalse(s.isGroupCollapsed("Prod"))
    }

    @Test
    fun renaming_and_deleting_reach_every_section() {
        // Host.group is a plain name: the same folder can hold shells and remote desktops, so the
        // side channel of empty folders must follow a rename/delete in both sections.
        val s = DesktopDesignState(
            initialCustomGroups = listOf(
                CustomGroup("Prod", HostSection.Terminal),
                CustomGroup("Prod", HostSection.RemoteDesktops),
            ),
        )
        s.renameGroupName("Prod", "Production")
        assertEquals(listOf("Production"), s.customGroupsIn(HostSection.RemoteDesktops))
        s.removeCustomGroup("Production")
        assertTrue(s.customGroups.isEmpty())
    }

    // Terminal font and size (Appearance → Font / Font size)

    @Test
    fun group_dialog_open_and_dismiss() {
        val s = DesktopDesignState()
        assertEquals(null, s.groupDialog)
        s.openCreateGroup(HostSection.RemoteDesktops)
        assertEquals(GroupDialog.Create(HostSection.RemoteDesktops), s.groupDialog)
        s.openRenameGroup("Prod")
        assertEquals(GroupDialog.Rename("Prod"), s.groupDialog)
        s.dismissGroupDialog()
        assertEquals(null, s.groupDialog)
    }
    @Test
    fun the_remote_desktop_panel_hides_and_comes_back() {
        val s = DesktopDesignState()

        assertFalse(s.remotePanelHidden)
        s.toggleRemotePanel()
        assertTrue(s.remotePanelHidden)
        // Independent of the hosts sidebar: they are different edges of the same screen.
        assertFalse(s.sidebarHidden)
        s.toggleRemotePanel()
        assertFalse(s.remotePanelHidden)
    }
}
