package app.skerry.ui.vault

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_player_open
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Desktop import via the native AWT [FileDialog] in LOAD mode — the mirror of [exportTextFile].
 * The modal dialog runs a nested EDT event loop, so it is shown on [Dispatchers.Swing] and the read
 * happens on IO. A file over [maxBytes] is rejected before it is read.
 */
actual suspend fun importTextFile(maxBytes: Int): String? {
    val title = getString(Res.string.term_player_open)
    val path = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { isVisible = true }
        val dir = dialog.directory ?: return@withContext null
        val name = dialog.file ?: return@withContext null
        File(dir, name).absolutePath
    } ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (file.length() > maxBytes) null else file.readText()
        }.getOrNull()
    }
}
