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
) : FileBrowser {

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

    private suspend fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SftpException) {
            throw FileBrowserException(e.message ?: "SFTP error", e)
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
