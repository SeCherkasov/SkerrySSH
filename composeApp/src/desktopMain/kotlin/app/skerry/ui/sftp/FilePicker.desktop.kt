package app.skerry.ui.sftp

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Desktop-реализация выбора файла нативным AWT [FileDialog]. На desktop выбранный путь и есть
 * реальная цель/источник передачи, поэтому handle тривиален: `stagingPath` = выбранный путь, а
 * `finalize`/`discard`/`cleanup` — no-op (промежуточный файл не нужен, в отличие от Android SAF).
 *
 * Диалог модальный: `isVisible = true` запускает вложенный цикл событий EDT и возвращается по
 * закрытию — поэтому показываем его на [Dispatchers.Swing] (поток EDT), а не блокируем произвольный.
 */
actual suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget? {
    val path = showFileDialog(FileDialog.SAVE, title = "Сохранить как", presetName = suggestedName)
        ?: return null
    return PathDownloadTarget(displayName = File(path).name, stagingPath = path)
}

actual suspend fun pickUploadSource(): UploadSource? {
    val path = showFileDialog(FileDialog.LOAD, title = "Выбрать файл для загрузки", presetName = null)
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
