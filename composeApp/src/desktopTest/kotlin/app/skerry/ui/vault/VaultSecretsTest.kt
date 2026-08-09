package app.skerry.ui.vault

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_delete
import app.skerry.ui.generated.resources.vtail_category_passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vault as a keychain you act on: which category a secret is listed under, and the delete that
 * reaches outside the vault — every host bound to the secret has to be unbound, not left pointing at
 * a record that is gone.
 *
 * The cascade is the reason this is a click test. A host left holding a deleted credential id does
 * not fail here — it fails later, at connect time, on whichever host it was.
 */
@OptIn(ExperimentalTestApi::class)
class VaultSecretsTest {

    @Test
    fun `a category lists only its own secrets`() = runDesktopShell {
        openVault()
        onNodeWithText(KEY_SECRET).assertIsDisplayed()
        onNodeWithText(PASSWORD_SECRET).assertDoesNotExist()

        onNodeWithText(string(Res.string.vtail_category_passwords)).performClick()
        waitForIdle()
        onNodeWithText(PASSWORD_SECRET).assertIsDisplayed()
        onNodeWithText(KEY_SECRET).assertDoesNotExist()
    }

    @Test
    fun `deleting a secret unbinds the hosts that used it`() = runDesktopShell { shell ->
        openVault()
        val credential = shell.credentialId(KEY_SECRET)
        assertTrue(shell.hosts.hosts.any { it.credentialId == credential })

        onNodeWithText(KEY_SECRET).performClick()
        waitForIdle()
        // The panel's button and the dialog's confirmation carry the same word; the dialog is last.
        onNodeWithText(string(Res.string.vault_delete)).performClick()
        waitForIdle()
        onAllNodes(hasText(string(Res.string.vault_delete)) and hasClickAction()).onLast().performClick()
        waitForIdle()

        assertNull(shell.labelOf(credential), "the secret itself must be gone")
        assertEquals(
            emptyList(),
            shell.hosts.hosts.filter { it.credentialId == credential }.map { it.label },
            "a host left pointing at a deleted secret fails at connect time, not here",
        )
        onNodeWithText(KEY_SECRET).assertDoesNotExist()
    }

    /** The keychain id behind a label — what a host actually points at. */
    private fun DesktopShell.credentialId(label: String): String =
        credentials.credentials.first { it.label == label }.id

    private fun DesktopShell.labelOf(id: String): String? =
        credentials.credentials.firstOrNull { it.id == id }?.label

    private fun ComposeUiTest.openVault() {
        onNodeWithTag(UiTags.railView(DesktopView.Vault)).performClick()
        waitForIdle()
    }
}

// Seeded vault ([app.skerry.ui.desktop.seededVault]): a key, a password, a certificate; the first
// is what the seeded hosts are bound to.
private const val KEY_SECRET = "work-laptop"
private const val PASSWORD_SECRET = "db-admin"
