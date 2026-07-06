package app.skerry.ui.vault

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Desktop export via the native AWT [FileDialog] (like [app.skerry.ui.sftp.pickDownloadTarget]).
 * The modal dialog runs a nested EDT event loop, so it's shown on [Dispatchers.Swing]; the write
 * happens on the IO dispatcher. Cancellation (directory/name null) returns `false`.
 */
actual suspend fun exportTextFile(suggestedName: String, content: String): Boolean {
    val path = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, "Сохранить как", FileDialog.SAVE).apply {
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
