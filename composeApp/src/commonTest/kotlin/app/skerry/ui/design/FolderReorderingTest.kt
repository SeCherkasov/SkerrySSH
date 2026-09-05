package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

private data class Card(val id: String, val group: String? = null)

/** A list that draws its no-folder bucket last (the snippet and runbook libraries). */
private object BucketLast : FolderItems<Card> {
    override fun idOf(item: Card): String = item.id
    override fun folderOf(item: Card): String? = item.group
    override fun withFolder(item: Card, folder: String?): Card = item.copy(group = folder)
    override fun canonicalName(folder: String?): String? = storedFolderName(folder)
    override val ungroupedLast: Boolean get() = true
}

/** A list that draws the bucket wherever its first item put it (the host sidebar). */
private object BucketInPlace : FolderItems<Card> {
    override fun idOf(item: Card): String = item.id
    override fun folderOf(item: Card): String? = item.group
    override fun withFolder(item: Card, folder: String?): Card = item.copy(group = folder)
    override fun canonicalName(folder: String?): String? = folder?.takeIf { it.isNotBlank() }
    override val ungroupedLast: Boolean get() = false
}

class FolderReorderingTest {

    private fun List<Card>.ids() = map { it.id }

    private fun move(items: List<Card>, id: String, group: String?, index: Int, adapter: FolderItems<Card> = BucketLast) =
        moveIntoFolder(items, adapter, setOf(id), group, index)

    // moveIntoFolder

    @Test
    fun a_row_dragged_inside_its_folder_changes_only_its_place() {
        val items = listOf(Card("a", "Ops"), Card("b", "Ops"), Card("c", "Ops"))

        assertEquals(listOf("c", "a", "b"), move(items, "c", "Ops", 0).ids())
    }

    @Test
    fun a_row_dragged_to_another_folder_is_refiled_under_it() {
        val items = listOf(Card("a", "Ops"), Card("b", "Ops"), Card("c", "Dev"))
        val result = move(items, "c", "Ops", 1)

        assertEquals(listOf("a", "c", "b"), result.ids())
        assertEquals("Ops", result.single { it.id == "c" }.group)
    }

    @Test
    fun a_row_dragged_out_of_every_folder_is_unfiled() {
        val items = listOf(Card("a", "Ops"), Card("b", "Ops"))
        val result = move(items, "a", null, 0)

        assertEquals(listOf("b", "a"), result.ids())
        assertEquals(null, result.single { it.id == "a" }.group)
    }

    @Test
    fun a_row_dragged_into_a_folder_that_has_none_of_its_own_yet_opens_it() {
        val items = listOf(Card("a", "Ops"), Card("b"))
        val result = move(items, "b", "Fresh", 0)

        assertEquals(listOf("a", "b"), result.ids())
        assertEquals("Fresh", result.single { it.id == "b" }.group)
    }

    @Test
    fun several_rows_move_as_one_block_in_their_own_order() {
        // Nothing in the UI selects two rows today, but the move takes a set and the callers may.
        val items = listOf(Card("a", "Ops"), Card("b", "Ops"), Card("c", "Dev"), Card("d", "Dev"), Card("e", "Dev"))
        val result = moveIntoFolder(items, BucketLast, setOf("c", "e"), "Ops", 1)

        assertEquals(listOf("a", "c", "e", "b", "d"), result.ids())
        assertEquals(listOf("Ops", "Ops"), result.filter { it.id in setOf("c", "e") }.map { it.group })
    }

    @Test
    fun a_drop_index_outside_the_folder_lands_at_its_nearest_edge() {
        // The index is computed from geometry that can be a frame behind the list (a sync apply
        // landing mid-drag), so it is clamped rather than trusted.
        val items = listOf(Card("a", "Ops"), Card("b", "Ops"), Card("c", "Ops"))

        assertEquals(listOf("c", "a", "b"), move(items, "c", "Ops", -5).ids())
        assertEquals(listOf("b", "c", "a"), move(items, "a", "Ops", 99).ids())
    }

    @Test
    fun a_move_of_nothing_or_of_an_unknown_row_leaves_the_list_alone() {
        val items = listOf(Card("a", "Ops"), Card("b"))

        assertEquals(items, moveIntoFolder(items, BucketLast, emptySet(), "Ops", 0))
        assertEquals(items, move(items, "gone", "Ops", 0))
    }

    @Test
    fun the_bucket_stays_last_after_a_move_that_empties_a_folder() {
        val items = listOf(Card("a"), Card("b", "Ops"))
        val result = move(items, "b", null, 0)

        assertEquals(listOf("b", "a"), result.ids())
    }

    @Test
    fun a_sidebar_keeps_its_bucket_where_its_first_row_put_it() {
        val items = listOf(Card("a"), Card("b", "Ops"), Card("c"))
        val result = move(items, "b", "Ops", 0, BucketInPlace)

        assertEquals(listOf("a", "c", "b"), result.ids())
    }

    // moveFolder

    @Test
    fun a_folder_moves_as_a_whole_and_keeps_its_rows_in_order() {
        val items = listOf(Card("a", "Alpha"), Card("b", "Alpha"), Card("c", "Beta"), Card("d", "Gamma"))

        assertEquals(listOf("d", "a", "b", "c"), moveFolder(items, BucketLast, "Gamma", 0).ids())
        assertEquals(listOf("c", "d", "a", "b"), moveFolder(items, BucketLast, "Alpha", 2).ids())
    }

    @Test
    fun a_folder_index_counts_the_folders_the_library_draws_not_the_ones_the_list_starts_with() {
        // The unfiled rows come first in the list but the library draws their bucket last, so the
        // drop index the user aimed at is counted against [Alpha, Beta, bucket]. Counting the list's
        // own order instead would file a folder one place off every time something is unfiled.
        val items = listOf(Card("u"), Card("a", "Alpha"), Card("b", "Beta"))

        assertEquals(listOf("b", "a", "u"), moveFolder(items, BucketLast, "Alpha", 1).ids())
    }

    @Test
    fun a_folder_index_outside_the_list_lands_at_its_nearest_end() {
        val items = listOf(Card("a", "Alpha"), Card("b", "Beta"))

        assertEquals(listOf("b", "a"), moveFolder(items, BucketLast, "Alpha", 9).ids())
        assertEquals(listOf("b", "a"), moveFolder(items, BucketLast, "Beta", -9).ids())
    }

    @Test
    fun moving_a_folder_the_list_does_not_have_leaves_it_alone() {
        val items = listOf(Card("a", "Alpha"), Card("b"))

        assertEquals(items, moveFolder(items, BucketLast, "Gone", 0))
    }

    // renameFolder

    @Test
    fun renaming_a_folder_refiles_every_row_in_it_and_nothing_else() {
        val items = listOf(Card("a", "Ops"), Card("b", "Ops"), Card("c", "Dev"))
        val result = renameFolder(items, BucketLast, "Ops", "Operations")

        assertEquals(listOf("Operations", "Operations", "Dev"), result.map { it.group })
        assertEquals(listOf("a", "b", "c"), result.ids())
    }

    @Test
    fun renaming_a_folder_onto_an_existing_one_merges_them_into_one_block() {
        val items = listOf(Card("a", "Ops"), Card("b", "Dev"), Card("c", "Ops"))
        val result = renameFolder(items, BucketLast, "Ops", "Dev")

        assertEquals(listOf("a", "b", "c"), result.ids())
        assertEquals(listOf("Dev", "Dev", "Dev"), result.map { it.group })
    }

    @Test
    fun clearing_a_folder_s_name_unfiles_its_rows_into_the_bucket() {
        // This is what "delete folder" runs: the folder goes, the records stay.
        val items = listOf(Card("a", "Ops"), Card("b", "Dev"))
        val result = renameFolder(items, BucketLast, "Ops", "")

        assertEquals(listOf("b", "a"), result.ids())
        assertEquals(null, result.single { it.id == "a" }.group)
    }

    @Test
    fun renaming_nothing_or_renaming_a_folder_to_itself_leaves_the_list_alone() {
        val items = listOf(Card("a", "Ops"), Card("b"))

        assertEquals(items, renameFolder(items, BucketLast, null, "Ops"))
        assertEquals(items, renameFolder(items, BucketLast, "  ", "Ops"))
        assertEquals(items, renameFolder(items, BucketLast, "Ops", "Ops"))
    }

    @Test
    fun a_folder_moves_past_the_ones_the_screen_shows_when_the_bucket_sits_between_them() {
        // A library nobody has reordered yet lists records in creation order, so the unfiled bucket
        // can sit mid-list even though the screen always draws it last. Counting folders in the
        // stored order rather than the drawn one made the first drag land where it started.
        val items = listOf(Card("a", "Alpha"), Card("b"), Card("c", "Beta"))
        val result = moveFolder(items, BucketLast, "Alpha", 1)

        assertEquals(listOf("c", "a", "b"), result.ids())
        assertEquals(listOf("Beta", "Alpha", UNGROUPED_FOLDER), foldersOf(result, ordered = true) { it.group }.map { it.name })
    }

    // Drops taken over a filtered list

    private val library = listOf(
        Card("a", "Ops"), Card("b", "Ops"), Card("c", "Ops"), Card("d", "Ops"), Card("e"),
    )

    private fun onScreen(visible: List<Card>) =
        FilteredFolderList(library, visible, { c: Card -> c.group }, { c: Card -> c.id })

    private fun fullIndex(visible: List<Card>, movingId: String, folder: String?, index: Int) =
        onScreen(visible).fullIndexInFolder(movingId, folder, index)

    @Test
    fun a_row_dropped_between_two_visible_rows_lands_between_them_in_the_full_list() {
        // Ops holds a, b, c, d; the search left b and d. Dropping d above b is index 0 among the
        // visible rows and index 1 in the folder — a and c are hidden and must not be jumped over.
        val visible = listOf(Card("b", "Ops"), Card("d", "Ops"))

        assertEquals(1, fullIndex(visible, "d", "Ops", 0))
    }

    @Test
    fun a_row_dropped_below_the_last_visible_row_lands_after_it_and_after_what_it_hides() {
        // Ops holds a, b, c, d; b, c and d are on screen. Dropping b below d is index 2 among the
        // two rows it is not, and index 3 in the folder — a stays where the filter hid it.
        val visible = listOf(Card("b", "Ops"), Card("c", "Ops"), Card("d", "Ops"))

        assertEquals(3, fullIndex(visible, "b", "Ops", 2))
    }

    @Test
    fun a_drop_into_a_folder_the_filter_emptied_goes_to_its_end() {
        assertEquals(4, fullIndex(emptyList(), "e", "Ops", 0))
    }

    @Test
    fun an_unfiltered_list_translates_to_the_index_it_was_given() {
        assertEquals(2, fullIndex(library, "d", "Ops", 2))
        assertEquals(0, fullIndex(library, "d", "Ops", 0))
    }

    @Test
    fun a_folder_dropped_between_two_visible_folders_counts_the_ones_the_filter_hid() {
        // Alpha, Beta, Gamma; the search left Alpha and Gamma. Dropping Alpha below Gamma is index 1
        // among the visible folders and index 2 among all of them.
        val all = listOf(Card("a", "Alpha"), Card("b", "Beta"), Card("c", "Gamma"))
        val visible = listOf(Card("a", "Alpha"), Card("c", "Gamma"))

        val list = FilteredFolderList(all, visible, { c: Card -> c.group }, { c: Card -> c.id })
        assertEquals(2, list.fullFolderIndex("Alpha", 1))
        // Above Gamma is index 1 among all folders, not 0: Beta is hidden and sits before it.
        assertEquals(1, list.fullFolderIndex("Alpha", 0))
    }

    @Test
    fun a_folder_drag_with_no_other_folder_on_screen_leaves_the_order_alone() {
        // Only Alpha's own header is drawn, so the gesture says nothing about where Alpha goes
        // relative to Beta. Answering "last" would step it over a folder the user cannot see.
        val all = listOf(Card("a", "Alpha"), Card("b", "Beta"))

        val list = FilteredFolderList(all, listOf(Card("a", "Alpha")), { c: Card -> c.group }, { c: Card -> c.id })
        assertEquals(0, list.fullFolderIndex("Alpha", 0))
    }

    @Test
    fun a_row_dragged_inside_a_folder_the_filter_left_alone_in_keeps_its_place() {
        // Ops holds a, b, c, d and the search left only b. Nothing on screen to place b against, so
        // the drop carries no order — sending it to the folder's end would jump it over c and d.
        val visible = listOf(Card("b", "Ops"))

        assertEquals(1, fullIndex(visible, "b", "Ops", 0))
        assertEquals(1, fullIndex(visible, "b", "Ops", 1))
    }

    // The bucket's own key

    @Test
    fun a_row_carrying_the_bucket_s_own_key_is_unfiled_here_too() {
        // Nothing this app writes holds it, but a record decoded from sync was written by a client
        // this one has no say over. [foldersOf] draws it in the bucket; a reordering that filed it
        // under the sentinel as a name would count it against a folder nobody sees.
        val items = listOf(Card("a", "Ops"), Card("b", UNGROUPED_FOLDER))
        val result = move(items, "a", null, 0)

        assertEquals(listOf("a", "b"), result.ids())
        assertEquals(listOf("a", "b"), foldersOf(result) { it.group }.single().items.ids())
    }
}
