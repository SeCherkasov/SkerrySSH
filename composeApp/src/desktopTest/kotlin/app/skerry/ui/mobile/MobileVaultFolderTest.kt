package app.skerry.ui.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.desktop.EmptyVault
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededVault
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import app.skerry.ui.generated.resources.vault_edit
import app.skerry.ui.generated.resources.vault_field_note
import app.skerry.ui.generated.resources.shtail_group_label
import app.skerry.ui.generated.resources.vault_save
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Filing a keychain secret into a folder on the phone — the desktop twin of
 * [app.skerry.ui.vault.VaultSecretsTest]. The sheet shares the edit dialog with the desktop and
 * wires its own select and its own "New group" overlay, which is the half a shared test cannot see.
 *
 * Mounted directly rather than through the mobile shell: the edit is refused unless a vault says it
 * is unlocked, and the shell harness has no vault at all.
 */
@OptIn(ExperimentalTestApi::class)
class MobileVaultFolderTest {

    @Test
    fun `a folder picked on the phone lands on the secret and the note stays where it was`() {
        val credentials = seededVault(BouncyCastleSshKeyGenerator())
        runForm({
            CompositionLocalProvider(LocalCredentials provides credentials, LocalVault provides EmptyVault) {
                MobileVaultScreen(MobileDesignState())
            }
        }) {
            onNodeWithText(KEY_SECRET).performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.vault_edit)).performClick()
            waitForIdle()
            onField(Res.string.vault_field_note).performTextInput(NOTE)
            onNodeWithText(string(Res.string.conn_group_none)).performClick()
            onNodeWithText(string(Res.string.conn_group_new)).performClick()
            waitForIdle()
            onNodeWithContentDescription(string(Res.string.shell_group_name_placeholder)).performTextInput(FOLDER)
            onNodeWithText(string(Res.string.conn_create)).performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.vault_save)).performClick()
            waitForIdle()

            val saved = credentials.credentials.first { it.label == KEY_SECRET }
            assertEquals(NOTE, saved.note, "the note and the folder were swapped on the way in")
            assertEquals(FOLDER, saved.group, "the folder picked on the phone never reached the record")

            // Creating writes the name at the screen's root; taking the secret back out of the
            // folder is the select's own line, wired by this screen and by no other.
            onNodeWithText(KEY_SECRET).performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.vault_edit)).performClick()
            waitForIdle()
            onNodeWithContentDescription("${string(Res.string.shtail_group_label)}, $FOLDER").performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.conn_group_none)).performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.vault_save)).performClick()
            waitForIdle()

            assertNull(
                credentials.credentials.first { it.label == KEY_SECRET }.group,
                "the select never cleared the folder",
            )
        }
    }
}

// Seeded vault (app.skerry.ui.desktop.seededVault): the private key is the first secret listed.
private const val KEY_SECRET = "work-laptop"
private const val FOLDER = "client-acme"
private const val NOTE = "rotate before the audit"
