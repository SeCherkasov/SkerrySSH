package app.skerry.ui.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostDropTargetingTest {

    // ──────────────────────────── hostDropTarget ────────────────────────────

    private val prod = FolderBounds(group = "Prod", top = 0f, bottom = 60f, otherHostCentersY = listOf(10f, 30f, 50f))
    private val lab = FolderBounds(group = "Lab", top = 60f, bottom = 100f, otherHostCentersY = listOf(70f, 90f))

    @Test
    fun empty_folders_yield_no_drop() {
        assertNull(hostDropTarget(emptyList(), pointerY = 42f))
    }

    @Test
    fun drop_between_two_hosts_of_a_folder_picks_the_index_after_those_above() {
        // pointerY=25 → выше центра 10, ниже 30/50 → index 1 в Prod.
        assertEquals(HostDrop("Prod", 1), hostDropTarget(listOf(prod, lab), pointerY = 25f))
    }

    @Test
    fun drop_at_the_top_of_a_folder_is_index_zero() {
        assertEquals(HostDrop("Prod", 0), hostDropTarget(listOf(prod, lab), pointerY = 5f))
    }

    @Test
    fun drop_at_the_bottom_of_a_folder_is_index_size() {
        // pointerY=58 → ниже всех центров Prod (10/30/50) → index 3.
        assertEquals(HostDrop("Prod", 3), hostDropTarget(listOf(prod, lab), pointerY = 58f))
    }

    @Test
    fun drop_inside_the_second_folder_targets_that_group() {
        assertEquals(HostDrop("Lab", 1), hostDropTarget(listOf(prod, lab), pointerY = 80f))
    }

    @Test
    fun pointer_above_everything_clamps_to_the_first_folder() {
        assertEquals(HostDrop("Prod", 0), hostDropTarget(listOf(prod, lab), pointerY = -100f))
    }

    @Test
    fun pointer_below_everything_clamps_to_the_last_folder() {
        assertEquals(HostDrop("Lab", 2), hostDropTarget(listOf(prod, lab), pointerY = 999f))
    }

    @Test
    fun folder_with_no_other_hosts_drops_at_index_zero() {
        // Та же папка, где единственный хост сейчас перетаскивается (otherHostCentersY пуст).
        val empty = FolderBounds("Solo", top = 0f, bottom = 40f, otherHostCentersY = emptyList())
        assertEquals(HostDrop("Solo", 0), hostDropTarget(listOf(empty), pointerY = 20f))
    }

    // ──────────────────────────── folderDropTarget ────────────────────────────

    @Test
    fun folder_drop_counts_headers_above_the_pointer() {
        val centers = listOf(10f, 30f, 50f)
        assertEquals(0, folderDropTarget(centers, pointerY = 0f))
        assertEquals(1, folderDropTarget(centers, pointerY = 20f))
        assertEquals(2, folderDropTarget(centers, pointerY = 40f))
        assertEquals(3, folderDropTarget(centers, pointerY = 100f))
    }
}
