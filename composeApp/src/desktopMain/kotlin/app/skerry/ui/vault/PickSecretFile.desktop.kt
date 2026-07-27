package app.skerry.ui.vault

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Desktop location picker: the native AWT [FileDialog] in LOAD mode, returning the absolute path
 * and reading nothing. Shown on [Dispatchers.Swing] because the modal dialog runs a nested EDT
 * event loop (same as [importTextFile]).
 */
actual suspend fun pickSecretFileRef(title: String): String? = withContext(Dispatchers.Swing) {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { isVisible = true }
    val dir = dialog.directory ?: return@withContext null
    val name = dialog.file ?: return@withContext null
    File(dir, name).absolutePath
}
