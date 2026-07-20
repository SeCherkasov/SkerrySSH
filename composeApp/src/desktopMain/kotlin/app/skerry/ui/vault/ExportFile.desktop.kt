package app.skerry.ui.vault

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_dialog_save_as
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Desktop export via the native AWT [FileDialog] (like [app.skerry.ui.sftp.pickDownloadTarget]).
 * The modal dialog runs a nested EDT event loop, so it's shown on [Dispatchers.Swing]; the write
 * happens on the IO dispatcher. Cancellation (directory/name null) returns `false`.
 */
actual suspend fun exportTextFile(suggestedName: String, content: String): Boolean {
    // Same dialog title as the SFTP download picker, so the shared key is reused.
    val title = getString(Res.string.sftp_dialog_save_as)
    val path = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory ?: return@withContext null
        val name = dialog.file ?: return@withContext null
        File(dir, name).absolutePath
    } ?: return false
    withContext(Dispatchers.IO) { File(path).writeText(content) }
    return true
}
