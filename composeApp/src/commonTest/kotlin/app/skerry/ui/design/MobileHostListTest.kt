package app.skerry.ui.design

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чистая логика списка Hosts мобильного макета `Skerry Mobile.html`: фильтр-чипы групп, поиск и
 * группировка в секции. Поведение зафиксировано здесь, переиспользуется компоновкой экрана.
 */
class MobileHostListTest {

    private fun host(
        id: String,
        label: String,
        address: String = "10.0.0.1",
        username: String = "root",
        group: String? = null,
    ) = Host(id = id, label = label, address = address, username = username, group = group)

    @Test
    fun empty_hosts_yield_only_all_chip_and_no_sections() {
        val view = buildMobileHostList(emptyList())
        assertEquals(listOf(ALL_HOSTS_CHIP), view.chips)
        assertEquals(emptyList(), view.sections)
    }

    @Test
    fun chips_are_all_plus_distinct_groups_in_first_appearance_order() {
        val hosts = listOf(
            host("1", "a", group = "Production"),
            host("2", "b", group = "Homelab"),
            host("3", "c", group = "Production"), // дубль группы не повторяется
            host("4", "d", group = null), // безгруппные не дают чип
            host("5", "e", group = "  "), // пустая группа не даёт чип
        )
        assertEquals(listOf(ALL_HOSTS_CHIP, "Production", "Homelab"), buildMobileHostList(hosts).chips)
    }

    @Test
    fun all_chip_keeps_every_host_grouped_into_sections() {
        val hosts = listOf(
            host("1", "web", group = "Production"),
            host("2", "pi", group = "Homelab"),
            host("3", "scratch", group = null),
        )
        val view = buildMobileHostList(hosts, activeChip = ALL_HOSTS_CHIP)
        assertEquals(listOf("Production", "Homelab", "Ungrouped"), view.sections.map { it.name })
        assertEquals(listOf("web"), view.sections[0].hosts.map { it.label })
        assertEquals(listOf("scratch"), view.sections[2].hosts.map { it.label })
    }

    @Test
    fun active_group_chip_filters_to_that_group_only() {
        val hosts = listOf(
            host("1", "web", group = "Production"),
            host("2", "pi", group = "Homelab"),
        )
        val view = buildMobileHostList(hosts, activeChip = "Homelab")
        assertEquals(listOf("Homelab"), view.sections.map { it.name })
        assertEquals(listOf("pi"), view.sections.single().hosts.map { it.label })
    }

    @Test
    fun query_matches_label_address_username_case_insensitively() {
        val hosts = listOf(
            host("1", "prod-web-01", address = "192.168.1.45", username = "root", group = "Production"),
            host("2", "db-master", address = "192.168.1.50", username = "admin", group = "Production"),
            host("3", "homelab-pi", address = "10.0.0.12", username = "pi", group = "Homelab"),
        )
        assertEquals(listOf("prod-web-01"), buildMobileHostList(hosts, query = "WEB").allHosts().map { it.label })
        assertEquals(listOf("db-master"), buildMobileHostList(hosts, query = "1.50").allHosts().map { it.label })
        assertEquals(listOf("homelab-pi"), buildMobileHostList(hosts, query = "PI").allHosts().map { it.label })
    }

    @Test
    fun query_and_chip_combine_with_and_semantics() {
        val hosts = listOf(
            host("1", "prod-web-01", group = "Production"),
            host("2", "homelab-web", group = "Homelab"),
        )
        // запрос "web" совпал бы с обоими, но активный чип сужает до Homelab
        val view = buildMobileHostList(hosts, query = "web", activeChip = "Homelab")
        assertEquals(listOf("homelab-web"), view.allHosts().map { it.label })
    }

    /** Все хосты всех секций по порядку — удобно для проверок фильтра. */
    private fun MobileHostList.allHosts() = sections.flatMap { it.hosts }
}
