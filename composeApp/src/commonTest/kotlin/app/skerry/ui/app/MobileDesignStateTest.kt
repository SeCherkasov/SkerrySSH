package app.skerry.ui.app

import app.skerry.shared.host.Host
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_LETTER_SPACING_MIN
import app.skerry.ui.terminal.TERMINAL_LINE_HEIGHT_MAX
import app.skerry.ui.terminal.TerminalFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.skerry.ui.mobile.MobileConnectDest
import app.skerry.ui.mobile.navigateAfterConnect

class MobileDesignStateTest {

    @Test
    fun defaults_open_on_hosts_with_tabs_visible() {
        val s = MobileDesignState()
        assertEquals(MobileTab.Hosts, s.tab)
        assertNull(s.route)
        assertFalse(s.sheetNewConn)
        assertTrue(s.showTabs)
    }

    // Terminal font and size (More → Appearance → Font / Font size)

    @Test
    fun terminal_font_defaults_to_hack_13px() {
        val s = MobileDesignState()
        assertEquals(TerminalFont.Hack, s.terminalFont)
        assertEquals(DEFAULT_TERMINAL_FONT_SIZE, s.terminalFontSize)
    }

    @Test
    fun terminal_font_honours_initial_values() {
        val s = MobileDesignState(initialTerminalFont = TerminalFont.JetBrainsMono, initialTerminalFontSize = 16)
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(16, s.terminalFontSize)
    }

    @Test
    fun chooseTerminalFont_updates_and_reports_once_skipping_repeat() {
        val seen = mutableListOf<TerminalFont>()
        val s = MobileDesignState(onTerminalFontChange = { seen += it })
        s.chooseTerminalFont(TerminalFont.JetBrainsMono)
        s.chooseTerminalFont(TerminalFont.JetBrainsMono) // repeat — no-op
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(listOf(TerminalFont.JetBrainsMono), seen)
    }

    @Test
    fun chooseTerminalFontSize_reports_skipping_repeat_and_out_of_range() {
        val seen = mutableListOf<Int>()
        val s = MobileDesignState(onTerminalFontSizeChange = { seen += it })
        s.chooseTerminalFontSize(16)
        s.chooseTerminalFontSize(16) // repeat — no-op
        s.chooseTerminalFontSize(99) // out of range — no-op
        s.chooseTerminalFontSize(11)
        assertEquals(11, s.terminalFontSize)
        assertEquals(listOf(16, 11), seen)
    }

    @Test
    fun chooseTerminalLineHeight_clamps_and_reports_skipping_repeat() {
        val seen = mutableListOf<Float>()
        val s = MobileDesignState(onTerminalLineHeightChange = { seen += it })
        assertEquals(DEFAULT_TERMINAL_LINE_HEIGHT, s.terminalLineHeight)
        s.chooseTerminalLineHeight(1.5f)
        s.chooseTerminalLineHeight(1.5f) // repeat — no-op
        s.chooseTerminalLineHeight(5f)   // out of range → clamp to MAX
        assertEquals(TERMINAL_LINE_HEIGHT_MAX, s.terminalLineHeight)
        assertEquals(listOf(1.5f, TERMINAL_LINE_HEIGHT_MAX), seen)
    }

    @Test
    fun chooseTerminalLetterSpacing_clamps_and_reports_skipping_repeat() {
        val seen = mutableListOf<Float>()
        val s = MobileDesignState(onTerminalLetterSpacingChange = { seen += it })
        assertEquals(DEFAULT_TERMINAL_LETTER_SPACING, s.terminalLetterSpacing)
        s.chooseTerminalLetterSpacing(1f)
        s.chooseTerminalLetterSpacing(1f)  // repeat — no-op
        s.chooseTerminalLetterSpacing(-9f) // out of range → clamp to MIN
        assertEquals(TERMINAL_LETTER_SPACING_MIN, s.terminalLetterSpacing)
        assertEquals(listOf(1f, TERMINAL_LETTER_SPACING_MIN), seen)
    }

    @Test
    fun four_tabs_in_template_order() {
        // Bottom navigation: 4 root tabs in this order. Files isn't in the bar — SFTP opens as a
        // push screen ([MobileRoute.Files]) from the host card, like the terminal.
        assertEquals(
            listOf(
                MobileTab.Hosts,
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
        assertFalse(s.showTabs) // push screens are full-screen, no tab bar
        s.pop()
        assertNull(s.route)
        assertTrue(s.showTabs)
    }

    @Test
    fun pop_returns_to_current_tab_not_hosts() {
        // A sub-screen (Ports) opened from the More tab → pop returns to More, not Hosts.
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
        s.select(MobileTab.Vault)
        assertEquals(MobileTab.Vault, s.tab)
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
        assertNull(s.selectedHostId) // otherwise 2B would read a stale id

        s.openHost("host-7")
        s.select(MobileTab.Snippets)
        assertNull(s.selectedHostId)
    }

    @Test
    fun navigate_after_connect_terminal_pushes_terminal_route() {
        // Connect from the host screen leads to the terminal push screen.
        val s = MobileDesignState()
        navigateAfterConnect(s, MobileConnectDest.Terminal)
        assertEquals(MobileRoute.Terminal, s.route)
    }

    @Test
    fun navigate_after_connect_files_pushes_files_route() {
        // SFTP from the host screen leads to the Files push screen (remote browser of the active
        // session) with a back arrow, like the terminal; pushing over host detail replaces its route.
        val s = MobileDesignState()
        s.push(MobileRoute.HostDetail)
        navigateAfterConnect(s, MobileConnectDest.Files)
        assertEquals(MobileRoute.Files, s.route)
        assertFalse(s.showTabs) // push screen is full-screen, no tab bar
    }

    @Test
    fun new_connection_sheet_opens_and_closes() {
        val s = MobileDesignState()
        s.openNewConn()
        assertTrue(s.sheetNewConn)
        s.closeSheet()
        assertFalse(s.sheetNewConn)
    }

    @Test
    fun open_new_conn_starts_in_create_mode() {
        // Creating a new host: the sheet opens with no editing profile (empty form).
        val s = MobileDesignState()
        s.openNewConn()
        assertTrue(s.sheetNewConn)
        assertNull(s.editingHost)
    }

    @Test
    fun open_edit_conn_opens_sheet_with_editing_host() {
        // Edit from the detail screen: the same sheet, but in edit mode for a specific profile.
        val s = MobileDesignState()
        val host = sampleHost()
        s.openEditConn(host)
        assertTrue(s.sheetNewConn)
        assertSame(host, s.editingHost)
    }

    @Test
    fun open_new_conn_clears_editing_host_after_edit() {
        // After an edit, hitting "+ New" again opens a clean form instead of sticking to the previous host.
        val s = MobileDesignState()
        s.openEditConn(sampleHost())
        s.openNewConn()
        assertTrue(s.sheetNewConn)
        assertNull(s.editingHost)
    }

    @Test
    fun close_sheet_clears_editing_host() {
        // Closing the sheet resets edit mode — otherwise the next "+ New" would inherit the id.
        val s = MobileDesignState()
        s.openEditConn(sampleHost())
        s.closeSheet()
        assertFalse(s.sheetNewConn)
        assertNull(s.editingHost)
    }

    // Collapsing host groups + persistence (desktop parity)

    @Test
    fun collapsed_groups_default_empty() {
        val s = MobileDesignState()
        assertTrue(s.collapsedGroups.isEmpty())
        assertFalse(s.isGroupCollapsed("Production"))
    }

    @Test
    fun collapsed_groups_honour_initial_value() {
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production"))
        assertTrue(s.isGroupCollapsed("Production"))
        assertFalse(s.isGroupCollapsed("Homelab"))
    }

    @Test
    fun toggle_group_collapsed_flips_membership_and_reports_to_callback() {
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(onCollapsedGroupsChange = { seen += it })
        s.toggleGroupCollapsed("A") // collapsed
        assertTrue(s.isGroupCollapsed("A"))
        s.toggleGroupCollapsed("B") // collapsed the second one
        s.toggleGroupCollapsed("A") // expanded the first one
        assertFalse(s.isGroupCollapsed("A"))
        assertTrue(s.isGroupCollapsed("B"))
        assertEquals(listOf(setOf("A"), setOf("A", "B"), setOf("B")), seen)
    }

    // Renaming/deleting host groups (pencil icon on the folder header; desktop parity)

    @Test
    fun rename_group_dialog_opens_and_dismisses() {
        val s = MobileDesignState()
        assertNull(s.renamingGroup)
        s.openRenameGroup("Production")
        assertEquals("Production", s.renamingGroup)
        s.dismissRenameGroup()
        assertNull(s.renamingGroup)
    }

    @Test
    fun on_group_renamed_moves_collapsed_membership_and_reports() {
        // A collapsed folder stays collapsed under the new name; persistence gets the updated set.
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production"), onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "Prod")
        assertFalse(s.isGroupCollapsed("Production"))
        assertTrue(s.isGroupCollapsed("Prod"))
        assertEquals(listOf(setOf("Prod")), seen)
    }

    @Test
    fun on_group_renamed_no_collapse_entry_is_silent() {
        // The group wasn't collapsed → nothing to sync, the persist callback stays silent.
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "Prod")
        assertTrue(seen.isEmpty())
    }

    @Test
    fun on_group_renamed_blank_or_unchanged_is_noop() {
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production"), onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "Production") // same name
        s.onGroupRenamed("Production", "   ")        // empty after trim
        assertTrue(s.isGroupCollapsed("Production"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun on_group_renamed_trims_surrounding_whitespace() {
        // Trailing whitespace is trimmed → the collapse key matches what the controller writes to Host.group.
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production"), onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "  Prod  ")
        assertTrue(s.isGroupCollapsed("Prod"))
        assertFalse(s.isGroupCollapsed("Production"))
        assertEquals(listOf(setOf("Prod")), seen)
    }

    @Test
    fun on_group_deleted_drops_collapsed_membership_and_reports() {
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production", "Homelab"), onCollapsedGroupsChange = { seen += it })
        s.onGroupDeleted("Production")
        assertFalse(s.isGroupCollapsed("Production"))
        assertTrue(s.isGroupCollapsed("Homelab"))
        assertEquals(listOf(setOf("Homelab")), seen)
    }

    @Test
    fun on_group_deleted_when_not_collapsed_is_silent() {
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(onCollapsedGroupsChange = { seen += it })
        s.onGroupDeleted("Production")
        assertTrue(seen.isEmpty())
    }

    // System back: where it leads based on navigation state (mobileBackAction)

    @Test
    fun back_on_hosts_root_yields_null_so_system_exits() {
        // Root Hosts tab with no open push screen: nothing to intercept — the event goes to the
        // system (normal app exit), the only exit point.
        assertNull(mobileBackAction(route = null, tab = MobileTab.Hosts))
    }

    @Test
    fun back_with_open_route_pops_it() {
        // Any full-screen push screen (terminal/detail/files/…) closes on back, returning to the tab.
        assertEquals(MobileBackAction.PopRoute, mobileBackAction(MobileRoute.Terminal, MobileTab.Hosts))
        assertEquals(MobileBackAction.PopRoute, mobileBackAction(MobileRoute.Files, MobileTab.More))
    }

    @Test
    fun back_on_non_hosts_tab_goes_home() {
        // From a non-root tab (Snippets/Vault/More), back returns to Hosts instead of exiting the app.
        assertEquals(MobileBackAction.GoHome, mobileBackAction(route = null, tab = MobileTab.Snippets))
        assertEquals(MobileBackAction.GoHome, mobileBackAction(route = null, tab = MobileTab.Vault))
        assertEquals(MobileBackAction.GoHome, mobileBackAction(route = null, tab = MobileTab.More))
    }

    @Test
    fun back_prefers_popping_route_over_tab() {
        // An open push screen takes priority over the tab: even on non-Hosts, back closes the screen first instead of jumping to Hosts.
        assertEquals(MobileBackAction.PopRoute, mobileBackAction(MobileRoute.Ports, MobileTab.More))
    }

    private fun sampleHost(): Host =
        Host(id = "host-42", label = "prod-web-01", address = "192.168.1.45", username = "root")
}
