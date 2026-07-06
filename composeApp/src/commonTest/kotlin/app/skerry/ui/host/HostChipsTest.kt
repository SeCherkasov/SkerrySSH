package app.skerry.ui.host

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tag filter-chip and search logic shared by the desktop sidebar and the mobile list. Folders are
 * grouped by [Host.group]; chips and filtering are by [Host.tags].
 */
class HostChipsTest {

    private fun host(
        id: String,
        label: String = id,
        address: String = "10.0.0.1",
        username: String = "root",
        group: String? = null,
        tags: List<String> = emptyList(),
    ) = Host(id = id, label = label, address = address, username = username, group = group, tags = tags)

    @Test
    fun chips_are_all_plus_distinct_tags_in_first_appearance_order() {
        val hosts = listOf(
            host("1", tags = listOf("prod", "web")),
            host("2", tags = listOf("docker", "prod")), // duplicate "prod" is not repeated
            host("3", tags = emptyList()), // no tags: yields no chips
        )
        assertEquals(listOf(ALL_HOSTS_CHIP, "prod", "web", "docker"), hostTagChips(hosts))
    }

    @Test
    fun empty_hosts_yield_only_all_chip() {
        assertEquals(listOf(ALL_HOSTS_CHIP), hostTagChips(emptyList()))
    }

    @Test
    fun chip_label_prefixes_tags_with_hash_but_keeps_all() {
        assertEquals("All", hostChipLabel(ALL_HOSTS_CHIP))
        assertEquals("#prod", hostChipLabel("prod"))
    }

    @Test
    fun all_chip_keeps_every_host() {
        val hosts = listOf(host("1", tags = listOf("prod")), host("2"))
        assertEquals(listOf("1", "2"), filterHosts(hosts, activeChip = ALL_HOSTS_CHIP).map { it.id })
    }

    @Test
    fun active_tag_chip_keeps_only_hosts_carrying_that_tag() {
        val hosts = listOf(
            host("1", tags = listOf("prod", "web")),
            host("2", tags = listOf("docker")),
            host("3", tags = listOf("prod")),
        )
        assertEquals(listOf("1", "3"), filterHosts(hosts, activeChip = "prod").map { it.id })
    }

    @Test
    fun query_matches_label_address_username_group_and_tags_case_insensitively() {
        val hosts = listOf(
            host("1", label = "prod-web-01", address = "192.168.1.45", username = "root", group = "Production", tags = listOf("edge")),
            host("2", label = "db-master", address = "192.168.1.50", username = "admin", group = "Production", tags = listOf("postgres")),
        )
        assertEquals(listOf("prod-web-01"), filterHosts(hosts, query = "WEB").map { it.label })
        assertEquals(listOf("db-master"), filterHosts(hosts, query = "1.50").map { it.label })
        assertEquals(listOf("prod-web-01"), filterHosts(hosts, query = "EDGE").map { it.label }) // matched by tag
        assertEquals(listOf("db-master"), filterHosts(hosts, query = "postgres").map { it.label })
    }

    @Test
    fun query_and_chip_combine_with_and_semantics() {
        val hosts = listOf(
            host("1", label = "prod-web", tags = listOf("prod")),
            host("2", label = "lab-web", tags = listOf("lab")),
        )
        assertEquals(listOf("lab-web"), filterHosts(hosts, activeChip = "lab", query = "web").map { it.label })
    }
}
