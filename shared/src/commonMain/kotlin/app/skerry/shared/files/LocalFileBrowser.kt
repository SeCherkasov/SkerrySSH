package app.skerry.shared.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException

/**
 * [FileBrowser] over the local filesystem via okio — shared code for desktop (JVM) and Android
 * (I/O behind [FileSystem]: the app passes `FileSystem.SYSTEM`, tests pass `FakeFileSystem`).
 * [home] is the panel's starting directory, returned by `realpath(".")`. Blocking I/O runs on
 * [ioDispatcher] (`Dispatchers.IO` in the app, `Dispatchers.Unconfined` in tests); okio I/O errors
 * are wrapped in [FileBrowserException] so the panel doesn't depend on a platform-specific type.
 */
class LocalFileBrowser(
    private val fileSystem: FileSystem,
    private val home: String,
    override val label: String,
    private val ioDispatcher: CoroutineDispatcher,
) : FileContentBrowser {

    /** Path normalization is pure (no I/O), so it doesn't run on [ioDispatcher]. */
    override suspend fun realpath(path: String): String =
        if (path == ".") home else path.toPath().normalized().toString()

    /** A file that disappears between [FileSystem.list] and [FileSystem.metadataOrNull] is dropped
     *  rather than shown as an Other entry with zeroed fields — `mapNotNull` filters missing metadata. */
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

    /** Recursive: okio `deleteRecursively` removes a file, symlink (as a link), or non-empty
     *  directory; a missing path raises `IOException` → [FileBrowserException]. Parity with the SFTP browser. */
    override suspend fun delete(item: FileItem): Unit = io {
        fileSystem.deleteRecursively(item.path.toPath(), mustExist = true)
    }

    override suspend fun rename(from: String, to: String): Unit = io {
        fileSystem.atomicMove(from.toPath(), to.toPath())
    }

    override suspend fun stat(path: String): FileItem? = io {
        val p = path.toPath()
        fileSystem.metadataOrNull(p)?.let { md ->
            FileItem(
                name = p.name,
                path = p.toString(),
                type = md.toItemType(),
                size = md.size ?: 0L,
                modifiedEpochSeconds = (md.lastModifiedAtMillis ?: 0L) / 1000L,
            )
        }
    }

    /** The size is checked before opening the file, so an oversized one is never read into memory. */
    override suspend fun readFile(path: String, maxBytes: Long): ByteArray = io {
        val p = path.toPath()
        val size = fileSystem.metadataOrNull(p)?.size
        if (size != null && size > maxBytes) {
            throw FileBrowserException(FileBrowserFailure.TooLarge, detail = "$size > $maxBytes")
        }
        val data = fileSystem.read(p) { readByteArray() }
        // Size unreported (or the file grew between the check and the read) — enforce the cap on the bytes.
        if (data.size > maxBytes) {
            throw FileBrowserException(FileBrowserFailure.TooLarge, detail = "${data.size} > $maxBytes")
        }
        data
    }

    override suspend fun writeFile(path: String, data: ByteArray): Unit = io {
        fileSystem.write(path.toPath()) { write(data) }
    }

    /**
     * Run blocking I/O on [ioDispatcher], wrapping okio [IOException] in [FileBrowserException]
     * ([FileBrowserFailure.LocalIo]; the okio text is kept as diagnostic detail only).
     * [CancellationException] is rethrown explicitly (not an [IOException], but kept for consistency
     * in case the catch is broadened later).
     */
    private suspend inline fun <T> io(crossinline block: () -> T): T =
        withContext(ioDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw FileBrowserException(FileBrowserFailure.LocalIo, e.message, e)
            }
        }
}

/** Object type from okio metadata; a symlink is detected by target before file/directory checks. */
private fun FileMetadata?.toItemType(): FileItemType = when {
    this == null -> FileItemType.Other
    symlinkTarget != null -> FileItemType.Symlink
    isDirectory -> FileItemType.Directory
    isRegularFile -> FileItemType.File
    else -> FileItemType.Other
}
