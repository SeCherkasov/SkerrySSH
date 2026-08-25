package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntryType

/**
 * What a transfer will move, worked out before a byte goes over the wire: [dirs] are the
 * directories to create, in pre-order (a parent always before its children), [files] the transfers
 * themselves, in order.
 */
internal class TransferPlan<T> {
    val dirs = mutableListOf<String>()
    val files = mutableListOf<T>()
}

/** One download task: [name] for the progress bar, remote [remotePath] to local [localPath]. */
internal data class DownloadTask(val name: String, val remotePath: String, val localPath: String, val size: Long)

/** One upload task: [name] for the progress bar, local [localPath] to remote [remotePath]. */
internal data class UploadTask(val name: String, val localPath: String, val remotePath: String, val size: Long)

/**
 * Builds the download plan for top-level [items] from remote [remoteDir] into local [localDir],
 * walking the remote tree through [sftp]. The top-level remote path is rebuilt here ([childPath]
 * from [remoteDir] + name), not trusted from the listing's `item.path` — same as for children in
 * [DownloadWalk].
 */
internal suspend fun buildDownloadPlan(
    sftp: SftpClient,
    items: List<FileItem>,
    localDir: String,
    remoteDir: String,
): TransferPlan<DownloadTask> {
    val walk = DownloadWalk(sftp)
    items.forEach { walk.walk(it.name, childPath(remoteDir, it.name), it.type, it.size, localDir) }
    return walk.plan
}

/**
 * Walks a remote tree entry, filling the plan it carries. A file becomes a download task; a directory adds a
 * local subdirectory and recurses; symlinks/other are skipped.
 *
 * Path-traversal guard against an untrusted server: [name] must be a plain name (no `/`/`\`
 * separators, not `.`/`..`, not empty). Child remote paths are rebuilt from the parent + a
 * validated name ([childPath]), never trusted from the listing's `child.path` — otherwise the
 * server could redirect the walk (and writes) outside the target tree.
 */
private class DownloadWalk(private val sftp: SftpClient) {
    val plan = TransferPlan<DownloadTask>()

    suspend fun walk(name: String, remotePath: String, type: FileItemType, size: Long, localDir: String) {
        if (isUnsafeListingName(name)) {
            throw FileBrowserException(FileBrowserFailure.IllegalName, detail = name)
        }
        val localPath = childPath(localDir, name)
        when (type) {
            FileItemType.File -> plan.files += DownloadTask(name, remotePath, localPath, size)
            FileItemType.Directory -> {
                plan.dirs += localPath
                // De-duplicated by name, not by path: the walk reads the SFTP client directly rather
                // than the browser that de-duplicates for the panel, and every local path it writes
                // is built from the name. A repeat would plan two tasks onto one local file, and the
                // second would overwrite the first while the progress bar counted two.
                sftp.list(remotePath).distinctBy { it.name }.forEach { child ->
                    walk(child.name, childPath(remotePath, child.name), child.type.toItemType(), child.size, localPath)
                }
            }
            FileItemType.Symlink, FileItemType.Other -> Unit
        }
    }
}

/** Builds the upload plan for top-level [items] into remote directory [remoteDir]. */
internal suspend fun buildUploadPlan(
    localBrowser: FileBrowser,
    items: List<FileItem>,
    remoteDir: String,
): TransferPlan<UploadTask> {
    val walk = UploadWalk(localBrowser)
    items.forEach { walk.walk(it.name, it.path, it.type, it.size, remoteDir) }
    return walk.plan
}

/**
 * Walks a local tree entry, filling the plan it carries (mirrors [DownloadWalk]). A file becomes an upload task;
 * a directory adds a remote subdirectory and recurses ([localBrowser] lists the local FS);
 * symlinks/other are skipped. Remote paths are rebuilt from [remoteDir] + a validated name; local
 * paths come from the trusted local listing.
 */
private class UploadWalk(private val localBrowser: FileBrowser) {
    val plan = TransferPlan<UploadTask>()

    suspend fun walk(name: String, localPath: String, type: FileItemType, size: Long, remoteDir: String) {
        if (isUnsafeListingName(name)) {
            throw FileBrowserException(FileBrowserFailure.IllegalName, detail = name)
        }
        val remotePath = childPath(remoteDir, name)
        when (type) {
            FileItemType.File -> plan.files += UploadTask(name, localPath, remotePath, size)
            FileItemType.Directory -> {
                plan.dirs += remotePath
                localBrowser.list(localPath).forEach { child ->
                    walk(child.name, child.path, child.type, child.size, remotePath)
                }
            }
            FileItemType.Symlink, FileItemType.Other -> Unit
        }
    }
}

/**
 * Creates directory [path] in [browser] (local or remote) if missing. `mkdir` without `-p` throws
 * on an already-existing directory, which is normal on a repeat transfer: a listing check confirms
 * the directory exists before the error is ignored; otherwise (no permission / it's a file) the
 * original mkdir error is rethrown.
 */
internal suspend fun ensureDir(browser: FileBrowser, path: String) {
    try {
        browser.mkdir(path)
    } catch (e: FileBrowserException) {
        try {
            browser.list(path)
        } catch (_: FileBrowserException) {
            throw e
        }
    }
}

/**
 * Safe remote path of [name] under [remoteDir] for delete operations: validates that [name] is a
 * plain name, then rebuilds the path from [remoteDir] (a pane directory snapshot), never trusting
 * server-controlled `item.path`.
 */
internal fun safeRemoteChild(name: String, remoteDir: String): String {
    if (isUnsafeListingName(name)) {
        throw FileBrowserException(FileBrowserFailure.IllegalName, detail = name)
    }
    return childPath(remoteDir, name)
}

/** Maps an SFTP entry type to the neutral [FileItemType] (for the download tree walk). */
private fun SftpEntryType.toItemType(): FileItemType = when (this) {
    SftpEntryType.File -> FileItemType.File
    SftpEntryType.Directory -> FileItemType.Directory
    SftpEntryType.Symlink -> FileItemType.Symlink
    SftpEntryType.Other -> FileItemType.Other
}
