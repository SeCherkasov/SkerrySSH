package app.skerry.ui.sftp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_copy_to_remote_q
import app.skerry.ui.generated.resources.sftp_move_to_remote_q
import kotlin.test.Test

/**
 * The copy/move question names the side the files go to as a whole phrase per direction, never a
 * pane badge spliced into a template: the badges are adverbs ("Remote") that read wrong in one.
 */
@OptIn(ExperimentalTestApi::class)
class SftpTransferTitleTest {

    @Test
    fun `copying from this computer asks about the server`() = runForm({
        ConfirmCopyDialog(listOf(FILE), toRemote = true, destPath = "/root", onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.sftp_copy_to_remote_q)).assertIsDisplayed()
    }

    @Test
    fun `moving from this computer asks about the server`() = runForm({
        ConfirmMoveDialog(listOf(FILE), toRemote = true, destPath = "/root", onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.sftp_move_to_remote_q)).assertIsDisplayed()
    }
}

private val FILE = FileItem(name = "photo.jpg", path = "/home/u/photo.jpg", type = FileItemType.File, size = 1, modifiedEpochSeconds = 0)
