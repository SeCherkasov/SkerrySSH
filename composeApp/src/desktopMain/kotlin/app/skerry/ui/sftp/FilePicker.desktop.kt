package app.skerry.ui.sftp

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Desktop-реализация выбора файла нативным AWT [FileDialog]. Диалог модальный: `isVisible = true`
 * запускает вложенный цикл событий EDT и возвращается по закрытию — поэтому показываем его на
 * [Dispatchers.Swing] (поток EDT), а не блокируем произвольный поток.
 */
actual suspend fun pickDownloadTarget(suggestedName: String): String? =
    showFileDialog(FileDialog.SAVE, title = "Сохранить как", presetName = suggestedName)

actual suspend fun pickUploadSource(): String? =
    showFileDialog(FileDialog.LOAD, title = "Выбрать файл для загрузки", presetName = null)

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
