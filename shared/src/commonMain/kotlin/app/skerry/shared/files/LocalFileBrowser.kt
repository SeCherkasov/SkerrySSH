package app.skerry.shared.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException

/**
 * [FileBrowser] над локальной файловой системой через okio — один код для desktop (JVM) и Android
 * (I/O спрятан за [FileSystem]: приложение подаёт `FileSystem.SYSTEM`, тесты — `FakeFileSystem`).
 * [home] — стартовый каталог панели, который возвращает `realpath(".")`. Блокирующий I/O уводится
 * на [ioDispatcher] (приложение — `Dispatchers.IO`, тесты — `Dispatchers.Unconfined`); okio-ошибки
 * I/O заворачиваются в [FileBrowserException], чтобы панель не зависела от платформенного типа.
 */
class LocalFileBrowser(
    private val fileSystem: FileSystem,
    private val home: String,
    override val label: String,
    private val ioDispatcher: CoroutineDispatcher,
) : FileBrowser {

    /** Нормализация путей — чистая (без I/O), поэтому не уходит на [ioDispatcher]. */
    override suspend fun realpath(path: String): String =
        if (path == ".") home else path.toPath().normalized().toString()

    /** Файл, исчезнувший между [FileSystem.list] и [FileSystem.metadataOrNull], отбрасывается (а не
     *  показывается «прочим» объектом с нулями) — `mapNotNull` фильтрует пропавшие метаданные. */
    override suspend fun list(path: String): List<FileItem> = io {
        fileSystem.list(path.toPath()).mapNotNull { p ->
            val md = fileSystem.metadataOrNull(p) ?: return@mapNotNull null
            FileItem(
                name = p.name,
                path = p.toString(),
                type = md.toItemType(),
                size = md.size ?: 0L,
                modifiedEpochSeconds = (md.lastModifiedAtMillis ?: 0L) / 1000L,
            )
        }
    }

    override suspend fun mkdir(path: String): Unit = io {
        fileSystem.createDirectory(path.toPath(), mustCreate = true)
    }

    /** okio `delete` снимает и файл, и пустой каталог; непустой каталог — `IOException` (как SFTP rmdir). */
    override suspend fun delete(item: FileItem): Unit = io {
        fileSystem.delete(item.path.toPath(), mustExist = true)
    }

    override suspend fun rename(from: String, to: String): Unit = io {
        fileSystem.atomicMove(from.toPath(), to.toPath())
    }

    /**
     * Выполнить блокирующий I/O на [ioDispatcher], завернув okio [IOException] в [FileBrowserException].
     * [CancellationException] пробрасывается явно (она не [IOException], но защищаем намерение от
     * будущего расширения catch — единый стиль с остальным кодом базы).
     */
    private suspend inline fun <T> io(crossinline block: () -> T): T =
        withContext(ioDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw FileBrowserException(e.message ?: "Ошибка файловой системы", e)
            }
        }
}

/** Тип объекта по okio-метаданным; симлинк определяется по цели раньше файла/каталога. */
private fun FileMetadata?.toItemType(): FileItemType = when {
    this == null -> FileItemType.Other
    symlinkTarget != null -> FileItemType.Symlink
    isDirectory -> FileItemType.Directory
    isRegularFile -> FileItemType.File
    else -> FileItemType.Other
}
