package app.skerry.ui.sftp

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_dialog_pick_upload
import app.skerry.ui.generated.resources.sftp_dialog_save_as
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * File picker backed by native AWT [FileDialog]. The selected path is the actual transfer
 * target/source, so the handle is trivial: `stagingPath` is the selected path and
 * `finalize`/`discard`/`cleanup` are no-ops (no staging file needed, unlike Android SAF).
 *
 * The dialog is modal: `isVisible = true` runs a nested EDT event loop and returns on close, so
 * it must run on [Dispatchers.Swing] (the EDT thread) rather than an arbitrary one.
 */
actual suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget? {
    val path = showFileDialog(FileDialog.SAVE, title = getString(Res.string.sftp_dialog_save_as), presetName = suggestedName)
        ?: return null
    return PathDownloadTarget(displayName = File(path).name, stagingPath = path)
}

actual suspend fun pickUploadSource(): UploadSource? {
    val path = showFileDialog(FileDialog.LOAD, title = getString(Res.string.sftp_dialog_pick_upload), presetName = null)
        ?: return null
    return PathUploadSource(name = File(path).name, stagingPath = path)
}

private class PathDownloadTarget(
    override val displayName: String,
    override val stagingPath: String,
) : DownloadTarget {
    override suspend fun finalize() = Unit
    override suspend fun discard() = Unit
}

private class PathUploadSource(
    override val name: String,
    override val stagingPath: String,
) : UploadSource {
    override suspend fun cleanup() = Unit
}

private suspend fun showFileDialog(mode: Int, title: String, presetName: String?): String? =
    withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, mode).apply {
            if (presetName != null) file = presetName
            isVisible = true
        }
        val dir = dialog.directory ?: return@withContext null
        val name = dialog.file ?: return@withContext null
        File(dir, name).absolutePath
    }
