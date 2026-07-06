package app.skerry.ui.host

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

private fun host(id: String, group: String? = null) =
    Host(id = id, label = id, address = "$id.example.com", username = "root", group = group)

/** Group name of each host — compact check that group blocks stay contiguous. */
private fun List<Host>.groupsOf() = map { it.group }

class HostReorderingTest {

    // moveHostToGroup: within a folder

    @Test
    fun moves_a_host_up_within_its_own_group() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"), host("c", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "c", targetGroup = "Prod", targetIndexInGroup = 0)

        assertEquals(listOf("c", "a", "b"), result.map { it.id })
    }

    @Test
    fun moves_a_host_down_within_its_own_group() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"), host("c", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "a", targetGroup = "Prod", targetIndexInGroup = 2)

        assertEquals(listOf("b", "c", "a"), result.map { it.id })
    }

    @Test
    fun clamps_target_index_past_the_end() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "a", targetGroup = "Prod", targetIndexInGroup = 99)

        assertEquals(listOf("b", "a"), result.map { it.id })
    }

    // moveHostToGroup: between folders

    @Test
    fun moves_a_host_into_another_group_and_rewrites_its_group() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"), host("x", "Lab"))

        val result = moveHostToGroup(hosts, hostId = "a", targetGroup = "Lab", targetIndexInGroup = 0)

        // a left Prod, is now first in Lab, its group is rewritten.
        assertEquals(listOf("b", "a", "x"), result.map { it.id })
        assertEquals(listOf("Prod", "Lab", "Lab"), result.groupsOf())
        assertEquals("Lab", result.first { it.id == "a" }.group)
    }

    @Test
    fun group_order_is_preserved_when_moving_between_existing_groups() {
        val hosts = listOf(host("a", "Prod"), host("x", "Lab"), host("b", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "x", targetGroup = "Prod", targetIndexInGroup = 1)

        // Prod appeared first, so it stays first; Lab became empty and disappeared.
        assertEquals(listOf("Prod"), result.groupsOf().distinct())
        assertEquals(listOf("a", "x", "b"), result.map { it.id })
    }

    @Test
    fun moving_the_last_host_out_of_a_group_drops_the_empty_group() {
        val hosts = listOf(host("solo", "Lab"), host("a", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "solo", targetGroup = "Prod", targetIndexInGroup = 1)

        assertEquals(listOf("a", "solo"), result.map { it.id })
        assertEquals(listOf("Prod", "Prod"), result.groupsOf())
    }

    @Test
    fun moves_a_host_into_the_ungrouped_bucket_with_null_group() {
        val hosts = listOf(host("loose", null), host("a", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "a", targetGroup = null, targetIndexInGroup = 0)

        assertEquals(listOf("a", "loose"), result.map { it.id })
        assertEquals(listOf(null, null), result.groupsOf())
    }

    @Test
    fun unknown_host_id_leaves_the_list_unchanged() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"))

        assertEquals(hosts, moveHostToGroup(hosts, hostId = "missing", targetGroup = "Lab", targetIndexInGroup = 0))
    }

    @Test
    fun blank_and_null_groups_are_one_ungrouped_bucket() {
        // Hosts with group="" and group=null are one "Ungrouped" folder; moving to null keeps them together.
        val hosts = listOf(host("blank", ""), host("nul", null), host("p", "Prod"))

        val result = moveHostToGroup(hosts, hostId = "p", targetGroup = null, targetIndexInGroup = 0)

        // "" and null are merged into one bucket, so all three end up adjacent; the raw group value on
        // the hosts themselves is not rewritten ([groupHostsByFolder] still folds them into Ungrouped).
        assertEquals(listOf("p", "blank", "nul"), result.map { it.id })
        assertEquals(listOf("Ungrouped"), groupHostsByFolder(result).map { it.name })
    }

    @Test
    fun preserves_credential_and_tags_on_the_moved_host() {
        val moved = Host("1", "a", "a.local", 22, "u", "Prod", credentialId = "cred-42", tags = listOf("prod"))
        val hosts = listOf(moved, host("x", "Lab"))

        val result = moveHostToGroup(hosts, hostId = "1", targetGroup = "Lab", targetIndexInGroup = 0)

        val after = result.first { it.id == "1" }
        assertEquals("cred-42", after.credentialId)
        assertEquals(listOf("prod"), after.tags)
        assertEquals("Lab", after.group)
    }

    // moveGroup

    @Test
    fun moves_a_whole_group_block_before_another() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"), host("x", "Lab"), host("y", "Lab"))

        val result = moveGroup(hosts, group = "Lab", targetGroupIndex = 0)

        assertEquals(listOf("x", "y", "a", "b"), result.map { it.id })
        assertEquals(listOf("Lab", "Lab", "Prod", "Prod"), result.groupsOf())
    }

    @Test
    fun moving_a_group_down_keeps_inner_host_order() {
        val hosts = listOf(host("a", "A"), host("b", "B"), host("c", "C"))

        val result = moveGroup(hosts, group = "A", targetGroupIndex = 2)

        assertEquals(listOf("B", "C", "A"), result.groupsOf())
        assertEquals(listOf("b", "c", "a"), result.map { it.id })
    }

    @Test
    fun moving_an_unknown_group_leaves_the_list_unchanged() {
        val hosts = listOf(host("a", "Prod"))

        assertEquals(hosts, moveGroup(hosts, group = "Nope", targetGroupIndex = 0))
    }

    @Test
    fun reorders_the_ungrouped_bucket_as_a_group() {
        val hosts = listOf(host("loose", null), host("a", "Prod"))

        val result = moveGroup(hosts, group = null, targetGroupIndex = 1)

        assertEquals(listOf("a", "loose"), result.map { it.id })
    }

    // renameHostGroup

    @Test
    fun renames_group_on_all_its_hosts() {
        val hosts = listOf(host("a", "Prod"), host("b", "Dev"), host("c", "Prod"))

        val result = renameHostGroup(hosts, oldName = "Prod", newName = "Production")

        assertEquals(listOf("Production", "Production", "Dev"), result.groupsOf())
        // Id set is preserved (reorder contract requirement).
        assertEquals(setOf("a", "b", "c"), result.map { it.id }.toSet())
    }

    @Test
    fun rename_to_blank_ungroups_hosts() {
        val hosts = listOf(host("a", "Prod"), host("b", "Prod"))

        val result = renameHostGroup(hosts, oldName = "Prod", newName = "")

        assertEquals(listOf(null, null), result.groupsOf())
    }

    @Test
    fun rename_merging_into_existing_group_keeps_blocks_contiguous() {
        val hosts = listOf(host("a", "Foo"), host("b", "Bar"), host("c", "Baz"))

        val result = renameHostGroup(hosts, oldName = "Baz", newName = "Foo")

        // c merges into Foo; the Foo block stays contiguous (a, c), then Bar.
        assertEquals(listOf("Foo", "Foo", "Bar"), result.groupsOf())
        assertEquals(listOf("a", "c", "b"), result.map { it.id })
    }

    @Test
    fun renaming_unknown_or_same_group_leaves_the_list_unchanged() {
        val hosts = listOf(host("a", "Prod"))

        assertEquals(hosts, renameHostGroup(hosts, oldName = "Nope", newName = "X"))
        assertEquals(hosts, renameHostGroup(hosts, oldName = "Prod", newName = "Prod"))
    }
}
