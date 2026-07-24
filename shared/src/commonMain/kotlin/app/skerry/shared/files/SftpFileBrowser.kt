package app.skerry.shared.files

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException

/**
 * Adapter from a remote [SftpClient] to the common [FileBrowser]: navigation/CRUD is passed through
 * as-is (the sshj implementation already runs I/O on `Dispatchers.IO`), [SftpEntry] maps to the
 * neutral [FileItem], and [SftpException] maps to [FileBrowserException] so the panel doesn't depend
 * on SFTP-specific types. File transfer isn't covered here: it goes through `SftpClient.download`/
 * `upload` in the dual-pane screen coordinator. [label] is the host name for the panel header.
 */
class SftpFileBrowser(
    private val sftp: SftpClient,
    override val label: String,
) : FileContentBrowser {

    override suspend fun realpath(path: String): String = guard { sftp.realpath(path) }

    override suspend fun list(path: String): List<FileItem> =
        guard { sftp.list(path).map { it.toFileItem() } }

    override suspend fun mkdir(path: String): Unit = guard { sftp.mkdir(path) }

    /**
     * Recursive delete: a directory is emptied first (contents removed by the same [deleteTree]),
     * then removed with `rmdir`; a file/symlink/other uses `remove` (`SSH_FXP_REMOVE` removes the
     * link itself, not its target — a symlink's target directory is not entered). SFTP has no
     * protocol-level recursive delete, so the traversal is client-side. Tree depth is unbounded:
     * pathologically deep trees could in theory overflow the stack.
     */
    override suspend fun delete(item: FileItem): Unit = guard {
        deleteTree(item.path, item.type == FileItemType.Directory)
    }

    /**
     * Traversal worker for [delete]. Called only from [delete] and relies on its [guard]: all SFTP
     * calls here throw [SftpException], caught by the outer [guard] (the whole recursion runs inside
     * its single try). Before descending into a child, verifies its path is actually nested under
     * [path] — otherwise a server returning a listing entry outside the directory (by bug or by
     * intent) could cause deletion of something the user didn't select.
     */
    private suspend fun deleteTree(path: String, isDirectory: Boolean) {
        if (!isDirectory) {
            sftp.remove(path)
            return
        }
        val prefix = if (path.endsWith("/")) path else "$path/"
        sftp.list(path).forEach { child ->
            if (!child.path.startsWith(prefix)) {
                throw SftpException("Listing $path returned a path outside the directory: ${child.path}")
            }
            deleteTree(child.path, child.type == SftpEntryType.Directory)
        }
        sftp.rmdir(path)
    }

    override suspend fun rename(from: String, to: String): Unit = guard { sftp.rename(from, to) }

    override suspend fun stat(path: String): FileItem? = guard { sftp.stat(path)?.toFileItem() }

    /**
     * The server's reported size is checked first, so an oversized file is never fetched, and the cap
     * is passed down to [SftpClient.read] which also enforces it while streaming — the size is
     * server-controlled, so a missing/understated one must not turn into an unbounded allocation.
     * The final check on the returned bytes covers a client that ignores the limit.
     */
    override suspend fun readFile(path: String, maxBytes: Long): ByteArray = guard {
        val reported = sftp.stat(path)?.size
        if (reported != null && reported > maxBytes) {
            throw FileBrowserException(FileBrowserFailure.TooLarge, detail = "$reported > $maxBytes")
        }
        val data = sftp.read(path, maxBytes)
        if (data.size > maxBytes) {
            throw FileBrowserException(FileBrowserFailure.TooLarge, detail = "${data.size} > $maxBytes")
        }
        data
    }

    override suspend fun writeFile(path: String, data: ByteArray): Unit = guard { sftp.write(path, data) }

    private suspend fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SftpException) {
            // The sshj/protocol text is diagnostic detail only; the UI renders [failure].
            throw FileBrowserException(FileBrowserFailure.Sftp, e.message, e)
        }
}

private fun SftpEntry.toFileItem(): FileItem =
    FileItem(
        name = name,
        path = path,
        type = type.toItemType(),
        size = size,
        modifiedEpochSeconds = modifiedEpochSeconds,
        permissions = permissions,
    )

private fun SftpEntryType.toItemType(): FileItemType = when (this) {
    SftpEntryType.File -> FileItemType.File
    SftpEntryType.Directory -> FileItemType.Directory
    SftpEntryType.Symlink -> FileItemType.Symlink
    SftpEntryType.Other -> FileItemType.Other
}
