package app.skerry.ui.sftp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_delete_file_body
import app.skerry.ui.generated.resources.sftp_overwrite_one
import app.skerry.ui.generated.resources.sftp_transfer_body
import app.skerry.ui.generated.resources.sftp_unprintable_name
import app.skerry.ui.generated.resources.sftp_what_single
import kotlin.test.Test

/**
 * The name in the confirmation that acts on a listing row.
 *
 * The row itself is filtered, and a dialog that is not would be worse than both being raw: the user
 * reads "Delete X?" over a row that says something else, and the irreversible action is the one
 * taken on the dialog's word. Copy, move and overwrite name the same server-chosen string.
 *
 * Written as escapes, never as the characters themselves.
 */
@OptIn(ExperimentalTestApi::class)
class SftpDialogNameTest {

    @Test
    fun `the delete confirmation names the file flattened`() = runForm({
        ConfirmDeleteItemsDialog(listOf(entry()), onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.sftp_delete_file_body, FLATTENED)).assertIsDisplayed()
        onNodeWithText(string(Res.string.sftp_delete_file_body, SPOOFED)).assertDoesNotExist()
    }

    @Test
    fun `the copy confirmation names the file flattened`() = runForm({
        ConfirmCopyDialog(listOf(entry()), destLabel = "this Mac", destPath = "/tmp", onConfirm = {}, onDismiss = {})
    }) {
        val what = string(Res.string.sftp_what_single, FLATTENED)
        onNodeWithText(string(Res.string.sftp_transfer_body, what, "/tmp")).assertIsDisplayed()
    }

    @Test
    fun `the move confirmation names the file flattened`() = runForm({
        ConfirmMoveDialog(listOf(entry()), destLabel = "this Mac", destPath = "/tmp", onConfirm = {}, onDismiss = {})
    }) {
        val what = string(Res.string.sftp_what_single, FLATTENED)
        onNodeWithText(string(Res.string.sftp_transfer_body, what, "/tmp")).assertIsDisplayed()
    }

    /** The phone deletes through a dialog of its own — the same name, a different composable. */
    @Test
    fun `the single-entry delete confirmation names the file flattened`() = runForm({
        ConfirmDeleteDialog(entry(), onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.sftp_delete_file_body, FLATTENED)).assertIsDisplayed()
        onNodeWithText(string(Res.string.sftp_delete_file_body, SPOOFED)).assertDoesNotExist()
    }

    /** A name that filters away entirely still has to name the row the dialog was opened from. */
    @Test
    fun `a name that filters away is named by the same stand-in as the row`() = runForm({
        ConfirmDeleteDialog(entry(name = "\u202E\u200B\uFEFF"), onConfirm = {}, onDismiss = {})
    }) {
        val unprintable = string(Res.string.sftp_unprintable_name)
        onNodeWithText(string(Res.string.sftp_delete_file_body, unprintable)).assertIsDisplayed()
    }

    /** The overwrite prompt decides which file is destroyed, and names it with the server's string. */
    @Test
    fun `the overwrite confirmation names the file flattened`() = runForm({
        ConfirmOverwriteDialog(names = listOf(SPOOFED), onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.sftp_overwrite_one, FLATTENED)).assertIsDisplayed()
        onNodeWithText(string(Res.string.sftp_overwrite_one, SPOOFED)).assertDoesNotExist()
    }
}

private fun entry(name: String = SPOOFED): FileItem =
    FileItem(name = name, path = "/var/www/$name", type = FileItemType.File, size = 10, modifiedEpochSeconds = 0)

/** U+202E before the tail: drawn as `invoiceexe.png`, the file it deletes ends in `.exe`. */
private const val SPOOFED = "invoice\u202Egnp.exe"

private const val FLATTENED = "invoicegnp.exe"
