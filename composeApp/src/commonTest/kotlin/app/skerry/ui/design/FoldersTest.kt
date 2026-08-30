package app.skerry.ui.design

import app.skerry.shared.text.normalizeGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class Row(val id: String, val group: String?)

class FoldersTest {

    private fun folders(vararg rows: Row) = foldersOf(rows.toList()) { it.group }

    @Test
    fun named_folders_appear_in_source_order_and_ungrouped_last() {
        val result = folders(
            Row("a", "staging"),
            Row("b", null),
            Row("c", "Production"),
            Row("d", "staging"),
        )

        assertEquals(listOf("staging", "Production", UNGROUPED_FOLDER), result.map { it.name })
        assertEquals(listOf("a", "d"), result[0].items.map { it.id })
        assertEquals(listOf("c"), result[1].items.map { it.id })
        assertEquals(listOf("b"), result[2].items.map { it.id })
    }

    @Test
    fun folders_preserve_order_of_first_appearance() {
        val result = folders(Row("a", "zebra"), Row("b", "Alpha"), Row("c", "beta"))

        assertEquals(listOf("zebra", "Alpha", "beta"), result.map { it.name })
    }

    @Test
    fun a_blank_group_is_no_group() {
        // Nothing stores a blank ([app.skerry.shared.text.normalizeGroup] maps it to null), but a
        // record arriving over sync from another client can hold one.
        val result = folders(Row("a", "   "), Row("b", ""))

        assertEquals(listOf(UNGROUPED_FOLDER), result.map { it.name })
        assertEquals(2, result.single().items.size)
    }

    @Test
    fun an_empty_list_has_no_folders_at_all() {
        assertEquals(emptyList(), folders())
    }

    @Test
    fun a_library_that_files_nothing_stays_flat() {
        val unfiled = listOf(Row("a", null), Row("b", ""))
        assertFalse(hasFolders(unfiled) { it.group })
        assertTrue(hasFolders(unfiled + Row("c", "prod")) { it.group })
    }

    @Test
    fun folder_names_deduplicate_and_sort_but_keep_the_case_they_were_typed_in() {
        assertEquals(
            listOf("Alpha", "beta", "beta-2"),
            folderNames(listOf("beta", null, "Alpha", "beta", "  ", "beta-2")),
        )
    }

    @Test
    fun two_lists_fold_their_same_named_folders_independently() {
        assertTrue(folderCollapseKey("snippet", "Production") != folderCollapseKey("runbook", "Production"))
        assertTrue(folderCollapseKey("vault/SSH_KEYS", "acme") != folderCollapseKey("vault/PASSWORDS", "acme"))
    }

    @Test
    fun a_folder_name_cannot_forge_another_list_s_key() {
        // The name reaches the key as a digest, so no name that can be typed — or pasted, or
        // written by another client — produces the key of a folder in another list and folds both.
        assertTrue(
            folderCollapseKey("snippet", "\u0000runbook\u0000Production") !=
                folderCollapseKey("runbook", "Production"),
        )
        assertFalse(normalizeGroup("\u0000runbook\u0000Production").orEmpty().contains('\u0000'))
    }

    @Test
    fun the_persisted_key_does_not_carry_the_folder_name() {
        // The fold state lands in a file outside the vault, readable while it is locked. A keychain
        // folder called `client-acme` is the metadata the payload is encrypted to keep.
        val key = folderCollapseKey("vault/PASSWORDS", "client-acme")

        assertFalse(key.contains("client-acme"))
        assertEquals(key, folderCollapseKey("vault/PASSWORDS", "client-acme"))
        assertTrue(folderCollapseKey("vault/PASSWORDS", "client-acmf") != key)
    }

    @Test
    fun a_folder_someone_names_ungrouped_is_a_folder_of_its_own() {
        val result = folders(Row("a", "Ungrouped"), Row("b", null))

        // Two sections, not one section drawn twice: the bucket is keyed by something no record can
        // hold, so folding the typed folder leaves the bucket open.
        assertEquals(listOf("Ungrouped", UNGROUPED_FOLDER), result.map { it.name })
        assertEquals(listOf("a"), result.first().items.map { it.id })
        assertEquals(listOf("b"), result.last().items.map { it.id })
        assertTrue(folderCollapseKey("snippet", "Ungrouped") != folderCollapseKey("snippet", UNGROUPED_FOLDER))
    }

    @Test
    fun a_record_carrying_the_bucket_s_own_key_falls_into_the_bucket() {
        // Nothing this app writes can hold it, but a record decoded from sync was written by a
        // client this one has no say over. Filing it as a name would draw a second bucket beside
        // the real one, sharing its header, its Compose key and its fold state.
        val result = folders(Row("a", UNGROUPED_FOLDER), Row("b", null))

        assertEquals(listOf(UNGROUPED_FOLDER), result.map { it.name })
        assertEquals(listOf("a", "b"), result.single().items.map { it.id })
        assertFalse(hasFolders(listOf(Row("a", UNGROUPED_FOLDER))) { it.group })
        assertEquals(emptyList(), folderNames(listOf(UNGROUPED_FOLDER)))
    }

    @Test
    fun the_bucket_key_is_not_a_name_a_record_can_hold() {
        // Whatever a user types, pastes or another client writes, normalization drops the NUL the
        // bucket is keyed by — so no record can be filed into the bucket's own section.
        assertTrue(normalizeGroup(UNGROUPED_FOLDER) != UNGROUPED_FOLDER)
        assertFalse(UNGROUPED_FOLDER.first().isLetterOrDigit())
    }
}
