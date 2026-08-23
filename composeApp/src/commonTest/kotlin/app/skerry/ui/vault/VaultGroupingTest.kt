package app.skerry.ui.vault

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.design.UNGROUPED_FOLDER
import app.skerry.ui.design.foldersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultGroupingTest {

    private fun secret(id: String, group: String? = null) =
        Credential(id = id, label = id, secret = CredentialSecret.Password("x"), group = group)

    @Test
    fun each_kind_folds_its_own_folders() {
        // Same folder name under two kinds: two sections on two screens, and folding one must not
        // fold the other.
        assertTrue(vaultFolderScope(VaultCategoryKind.SSH_KEYS) != vaultFolderScope(VaultCategoryKind.PASSWORDS))
    }

    @Test
    fun the_select_offers_folders_from_every_kind() {
        // A password filed next to the key of the same customer must reuse that folder's name
        // rather than have it retyped — which is how `acme` and `Acme` become two folders.
        val all = listOf(secret("k", "client-acme"), secret("p", "staging"), secret("q"))

        assertEquals(listOf("client-acme", "staging"), credentialFolders(all))
    }

    @Test
    fun secrets_with_no_folder_land_in_the_ungrouped_bucket_last() {
        val folders = foldersOf(listOf(secret("a"), secret("b", "client-acme"))) { it.group }

        assertEquals(listOf("client-acme", UNGROUPED_FOLDER), folders.map { it.name })
        assertEquals(listOf("a"), folders.last().items.map { it.id })
    }
}
