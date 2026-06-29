package app.skerry.ui.design

import app.skerry.shared.host.Host
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.TerminalFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // Шрифт и кегль терминала (More → Appearance → Font / Font size)

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
        s.chooseTerminalFont(TerminalFont.JetBrainsMono) // повтор — no-op
        assertEquals(TerminalFont.JetBrainsMono, s.terminalFont)
        assertEquals(listOf(TerminalFont.JetBrainsMono), seen)
    }

    @Test
    fun chooseTerminalFontSize_reports_skipping_repeat_and_out_of_range() {
        val seen = mutableListOf<Int>()
        val s = MobileDesignState(onTerminalFontSizeChange = { seen += it })
        s.chooseTerminalFontSize(16)
        s.chooseTerminalFontSize(16) // повтор — no-op
        s.chooseTerminalFontSize(99) // вне диапазона — no-op
        s.chooseTerminalFontSize(11)
        assertEquals(11, s.terminalFontSize)
        assertEquals(listOf(16, 11), seen)
    }

    @Test
    fun four_tabs_in_template_order() {
        // Нижняя навигация: 4 корневых таба в этом порядке. Files в баре нет — SFTP открывается
        // push-экраном ([MobileRoute.Files]) с карточки хоста, как терминал.
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
        assertNull(s.selectedHostId) // иначе 2B прочёл бы устаревший id

        s.openHost("host-7")
        s.select(MobileTab.Snippets)
        assertNull(s.selectedHostId)
    }

    @Test
    fun navigate_after_connect_terminal_pushes_terminal_route() {
        // Connect с экрана хоста ведёт на push-экран терминала.
        val s = MobileDesignState()
        navigateAfterConnect(s, MobileConnectDest.Terminal)
        assertEquals(MobileRoute.Terminal, s.route)
    }

    @Test
    fun navigate_after_connect_files_pushes_files_route() {
        // SFTP с экрана хоста ведёт на push-экран Files (Remote-браузер активной сессии) с back-стрелкой,
        // как терминал; push поверх детали хоста заменяет её маршрут.
        val s = MobileDesignState()
        s.push(MobileRoute.HostDetail)
        navigateAfterConnect(s, MobileConnectDest.Files)
        assertEquals(MobileRoute.Files, s.route)
        assertFalse(s.showTabs) // push-экран полноэкранный, без таб-бара
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
        // Создание нового хоста: лист открыт без редактируемого профиля (форма пустая).
        val s = MobileDesignState()
        s.openNewConn()
        assertTrue(s.sheetNewConn)
        assertNull(s.editingHost)
    }

    @Test
    fun open_edit_conn_opens_sheet_with_editing_host() {
        // Edit с экрана детали: тот же лист, но в режиме правки конкретного профиля.
        val s = MobileDesignState()
        val host = sampleHost()
        s.openEditConn(host)
        assertTrue(s.sheetNewConn)
        assertSame(host, s.editingHost)
    }

    @Test
    fun open_new_conn_clears_editing_host_after_edit() {
        // После правки повторный «+ New» открывает чистую форму, а не залипает на прежнем хосте.
        val s = MobileDesignState()
        s.openEditConn(sampleHost())
        s.openNewConn()
        assertTrue(s.sheetNewConn)
        assertNull(s.editingHost)
    }

    @Test
    fun close_sheet_clears_editing_host() {
        // Закрытие листа сбрасывает режим правки — иначе следующий «+ New» унаследовал бы id.
        val s = MobileDesignState()
        s.openEditConn(sampleHost())
        s.closeSheet()
        assertFalse(s.sheetNewConn)
        assertNull(s.editingHost)
    }

    // Схлопывание папок хостов + персист (паритет desktop)

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
        s.toggleGroupCollapsed("A") // свернули
        assertTrue(s.isGroupCollapsed("A"))
        s.toggleGroupCollapsed("B") // свернули вторую
        s.toggleGroupCollapsed("A") // развернули первую
        assertFalse(s.isGroupCollapsed("A"))
        assertTrue(s.isGroupCollapsed("B"))
        assertEquals(listOf(setOf("A"), setOf("A", "B"), setOf("B")), seen)
    }

    // Переименование/удаление групп хостов (карандаш у заголовка папки; паритет desktop)

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
        // Свёрнутая папка остаётся свёрнутой под новым именем; персист получает обновлённый набор.
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production"), onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "Prod")
        assertFalse(s.isGroupCollapsed("Production"))
        assertTrue(s.isGroupCollapsed("Prod"))
        assertEquals(listOf(setOf("Prod")), seen)
    }

    @Test
    fun on_group_renamed_no_collapse_entry_is_silent() {
        // Группа не была свёрнута → синхронить нечего, колбэк персиста молчит.
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "Prod")
        assertTrue(seen.isEmpty())
    }

    @Test
    fun on_group_renamed_blank_or_unchanged_is_noop() {
        val seen = mutableListOf<Set<String>>()
        val s = MobileDesignState(initialCollapsedGroups = setOf("Production"), onCollapsedGroupsChange = { seen += it })
        s.onGroupRenamed("Production", "Production") // то же имя
        s.onGroupRenamed("Production", "   ")        // пустое после trim
        assertTrue(s.isGroupCollapsed("Production"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun on_group_renamed_trims_surrounding_whitespace() {
        // Хвостовые пробелы режутся → ключ свёрнутости совпадает с тем, что контроллер запишет в Host.group.
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

    private fun sampleHost(): Host =
        Host(id = "host-42", label = "prod-web-01", address = "192.168.1.45", username = "root")
}
