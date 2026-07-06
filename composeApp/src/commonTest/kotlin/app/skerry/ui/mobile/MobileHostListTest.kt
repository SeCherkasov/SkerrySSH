package app.skerry.ui.mobile

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import app.skerry.ui.host.ALL_HOSTS_CHIP

/**
 * Logic for the mobile Hosts list: tag filter-chips, search, and grouping into sections by group.
 * Chip/filter semantics are shared with [HostChipsTest]; this covers the section grouping.
 */
class MobileHostListTest {

    private fun host(
        id: String,
        label: String,
        address: String = "10.0.0.1",
        username: String = "root",
        group: String? = null,
        tags: List<String> = emptyList(),
    ) = Host(id = id, label = label, address = address, username = username, group = group, tags = tags)

    @Test
    fun empty_hosts_yield_only_all_chip_and_no_sections() {
        val view = buildMobileHostList(emptyList())
        assertEquals(listOf(ALL_HOSTS_CHIP), view.chips)
        assertEquals(emptyList(), view.sections)
    }

    @Test
    fun chips_are_all_plus_distinct_tags() {
        val hosts = listOf(
            host("1", "a", tags = listOf("prod", "web")),
            host("2", "b", tags = listOf("docker", "prod")), // duplicate "prod" is not repeated
            host("3", "c", tags = emptyList()), // no tags: yields no chips
        )
        assertEquals(listOf(ALL_HOSTS_CHIP, "prod", "web", "docker"), buildMobileHostList(hosts).chips)
    }

    @Test
    fun all_chip_keeps_every_host_grouped_into_sections_by_group() {
        val hosts = listOf(
            host("1", "web", group = "Production", tags = listOf("prod")),
            host("2", "pi", group = "Homelab"),
            host("3", "scratch", group = null),
        )
        val view = buildMobileHostList(hosts, activeChip = ALL_HOSTS_CHIP)
        assertEquals(listOf("Production", "Homelab", "Ungrouped"), view.sections.map { it.name })
        assertEquals(listOf("web"), view.sections[0].hosts.map { it.label })
        assertEquals(listOf("scratch"), view.sections[2].hosts.map { it.label })
    }

    @Test
    fun active_tag_chip_filters_to_hosts_with_that_tag_keeping_group_sections() {
        val hosts = listOf(
            host("1", "web", group = "Production", tags = listOf("prod")),
            host("2", "pi", group = "Homelab", tags = listOf("lab")),
            host("3", "db", group = "Production", tags = listOf("prod")),
        )
        val view = buildMobileHostList(hosts, activeChip = "prod")
        assertEquals(listOf("Production"), view.sections.map { it.name })
        assertEquals(listOf("web", "db"), view.sections.single().hosts.map { it.label })
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
            host("1", "prod-web-01", tags = listOf("prod")),
            host("2", "homelab-web", tags = listOf("lab")),
        )
        // query "web" would match both, but the active tag chip narrows to lab
        val view = buildMobileHostList(hosts, query = "web", activeChip = "lab")
        assertEquals(listOf("homelab-web"), view.allHosts().map { it.label })
    }

    /** All hosts across sections in order; convenient for filter assertions. */
    private fun MobileHostList.allHosts() = sections.flatMap { it.hosts }
}
