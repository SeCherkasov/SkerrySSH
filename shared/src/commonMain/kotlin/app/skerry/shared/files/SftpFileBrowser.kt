package app.skerry.shared.files

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException

/**
 * Адаптер удалённого [SftpClient] к общему [FileBrowser]: навигация/CRUD пробрасываются как есть
 * (sshj-реализация уже уводит I/O на `Dispatchers.IO`), [SftpEntry] маппится в нейтральный
 * [FileItem], а [SftpException] — в [FileBrowserException], чтобы панель не зависела от SFTP-типов.
 * Передачу файлов адаптер не покрывает: она идёт через `SftpClient.download`/`upload` в координаторе
 * двухпанельного экрана. [label] — имя хоста для заголовка панели.
 */
class SftpFileBrowser(
    private val sftp: SftpClient,
    override val label: String,
) : FileBrowser {

    override suspend fun realpath(path: String): String = guard { sftp.realpath(path) }

    override suspend fun list(path: String): List<FileItem> =
        guard { sftp.list(path).map { it.toFileItem() } }

    override suspend fun mkdir(path: String): Unit = guard { sftp.mkdir(path) }

    /**
     * Каталог снимается `rmdir` (только пустой); файл/симлинк/прочее — `remove` (`SSH_FXP_REMOVE`
     * убирает сам линк, не цель). Exhaustive `when`: новый [FileItemType] заставит дописать ветку.
     */
    override suspend fun delete(item: FileItem): Unit = guard {
        when (item.type) {
            FileItemType.Directory -> sftp.rmdir(item.path)
            FileItemType.File, FileItemType.Symlink, FileItemType.Other -> sftp.remove(item.path)
        }
    }

    override suspend fun rename(from: String, to: String): Unit = guard { sftp.rename(from, to) }

    private suspend fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SftpException) {
            throw FileBrowserException(e.message ?: "Ошибка SFTP", e)
        }
}

private fun SftpEntry.toFileItem(): FileItem =
    FileItem(name = name, path = path, type = type.toItemType(), size = size, modifiedEpochSeconds = modifiedEpochSeconds)

private fun SftpEntryType.toItemType(): FileItemType = when (this) {
    SftpEntryType.File -> FileItemType.File
    SftpEntryType.Directory -> FileItemType.Directory
    SftpEntryType.Symlink -> FileItemType.Symlink
    SftpEntryType.Other -> FileItemType.Other
}
