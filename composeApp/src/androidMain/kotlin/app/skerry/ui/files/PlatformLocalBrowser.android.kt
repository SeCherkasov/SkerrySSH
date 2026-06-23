package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.LocalFileBrowser
import app.skerry.ui.sftp.SafBridge
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

/**
 * Android: локальная панель Files над приватной папкой приложения. Под scoped storage (Android 11+)
 * прямой доступ к `/storage/emulated/0` запрещён без `MANAGE_EXTERNAL_STORAGE`, поэтому корень —
 * app-specific external files dir (`Android/data/app.skerry/files`): доступен на чтение/запись без
 * разрешений и переживает перезапуск. Сюда же «Download to device» (downloadSelection) кладёт скачанное,
 * так что оно сразу видно в Local; скачивание наружу из песочницы идёт отдельным путём «Save to…»
 * ([pickDownloadTarget] → [TransferCoordinator.downloadToTarget]).
 *
 * Контекст берём из [SafBridge] (его ставит Activity в `onCreate`); до установки — fallback на корень
 * внешнего хранилища (панель просто покажет ошибку доступа вместо краша). `getExternalFilesDir` создаёт
 * каталог при первом обращении, поэтому панель не упадёт на пустом пути.
 */
actual fun platformLocalBrowser(): FileBrowser {
    val ctx = SafBridge.context()
    val home = ctx?.getExternalFilesDir(null)?.absolutePath
        ?: ctx?.filesDir?.absolutePath
        ?: "/storage/emulated/0"
    return LocalFileBrowser(
        fileSystem = FileSystem.SYSTEM,
        home = home,
        label = "На устройстве",
        ioDispatcher = Dispatchers.IO,
    )
}
