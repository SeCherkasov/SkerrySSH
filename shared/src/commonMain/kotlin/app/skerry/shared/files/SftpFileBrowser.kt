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
     * Рекурсивное удаление: каталог сначала очищается изнутри (содержимое снимается тем же
     * [deleteTree]), затем снимается пустым `rmdir`; файл/симлинк/прочее — `remove` (`SSH_FXP_REMOVE`
     * убирает сам линк, не цель — в каталог-цель симлинка не заходим). Протокол SFTP сам по себе
     * непустой каталог снять не умеет, поэтому обход — на клиенте. Глубину дерева не ограничиваем:
     * патологически глубокие деревья (тысячи уровней) теоретически переполнят стек — приемлемо для MVP.
     */
    override suspend fun delete(item: FileItem): Unit = guard {
        deleteTree(item.path, item.type == FileItemType.Directory)
    }

    /**
     * Воркер обхода [delete]. Вызывается ТОЛЬКО из [delete] и опирается на его [guard]: все
     * SFTP-вызовы здесь бросают [SftpException], которую заворачивает внешний [guard] (вся рекурсия
     * исполняется внутри одного его try). Перед спуском к ребёнку проверяем, что его путь реально
     * вложен в [path] — иначе сервер, вернувший в листинге путь вне каталога (по ошибке или злонамеренно),
     * заставил бы удалить не то, что выбрал пользователь.
     */
    private suspend fun deleteTree(path: String, isDirectory: Boolean) {
        if (!isDirectory) {
            sftp.remove(path)
            return
        }
        val prefix = if (path.endsWith("/")) path else "$path/"
        sftp.list(path).forEach { child ->
            if (!child.path.startsWith(prefix)) {
                throw SftpException("Листинг $path вернул путь вне каталога: ${child.path}")
            }
            deleteTree(child.path, child.type == SftpEntryType.Directory)
        }
        sftp.rmdir(path)
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
