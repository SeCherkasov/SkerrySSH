package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.MAX_LISTING_ENTRIES
import app.skerry.shared.files.TreeWalkLimit
import app.skerry.shared.files.refuseOversizedListing
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

/**
 * What a walk knows about one entry before it plans it: the listing's [name], [type] and [size], and
 * nothing else. Deliberately not a [FileItem] — that carries a path, and on the remote side the path
 * in a listing is the server's to choose; every path a walk uses is rebuilt from a validated name.
 */
private class WalkEntry(val name: String, val type: FileItemType, val size: Long)

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
    items.forEach {
        val entry = WalkEntry(it.name, it.type, it.size)
        // Each selected item is walked in turn, so each one starts with the whole listing budget:
        // what the one before it held is gone by the time this call runs.
        walk.walk(entry, childPath(remoteDir, it.name), localDir, depth = 0, hold = MAX_LISTING_ENTRIES)
    }
    return walk.plan
}

/**
 * Walks a remote tree entry, filling the plan it carries. A file becomes a download task; a directory adds a
 * local subdirectory and recurses; symlinks/other are skipped. [depth] is how far below the selected
 * item this entry sits, checked against [TreeWalkLimit] before descending.
 *
 * Path-traversal guard against an untrusted server: [name] must be a plain name (no `/`/`\`
 * separators, not `.`/`..`, not empty). Child remote paths are rebuilt from the parent + a
 * validated name ([childPath]), never trusted from the listing's `child.path` — otherwise the
 * server could redirect the walk (and writes) outside the target tree.
 */
private class DownloadWalk(private val sftp: SftpClient) {
    val plan = TransferPlan<DownloadTask>()
    private val limit = TreeWalkLimit()

    /**
     * [hold] is what is left of [MAX_LISTING_ENTRIES] for the levels this call is inside, the same
     * budget [app.skerry.shared.files.SftpFileBrowser] runs its delete under. The plan's own count
     * cannot stand in for it: that counts entries the walk has *taken on*, and a descent into the
     * first child of a level counts one entry while the whole listing of every level above stays
     * alive in its frame. Sixty-four of those is a plan that never gets built because the heap went
     * first, and an `OutOfMemoryError` is not a refusal anyone can read.
     */
    suspend fun walk(
        entry: WalkEntry,
        remotePath: String,
        localDir: String,
        depth: Int,
        hold: Int,
    ) {
        if (isUnsafeListingName(entry.name)) {
            throw FileBrowserException(FileBrowserFailure.IllegalName, detail = entry.name)
        }
        val localPath = childPath(localDir, entry.name)
        when (entry.type) {
            FileItemType.File -> {
                limit.count()
                plan.files += DownloadTask(entry.name, remotePath, localPath, entry.size)
            }
            FileItemType.Directory -> {
                limit.count()
                limit.descend(depth + 1)
                plan.dirs += localPath
                // De-duplicated by name, not by path: the walk reads the SFTP client directly rather
                // than the browser that de-duplicates for the panel, and every local path it writes
                // is built from the name. A repeat would plan two tasks onto one local file, and the
                // second would overwrite the first while the progress bar counted two.
                // The listing is bounded as it is read, not after: the plan's own entry cap is
                // checked per entry taken on, which is too late to stop a server answering one
                // directory with more names than the client can hold.
                val children = sftp.list(remotePath, hold)
                refuseOversizedListing(remotePath, children.size, hold)
                val childHold = hold - children.size
                children.distinctBy { it.name }.forEach { child ->
                    val next = WalkEntry(child.name, child.type.toItemType(), child.size)
                    walk(next, childPath(remotePath, child.name), localPath, depth + 1, childHold)
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
    items.forEach { walk.walk(WalkEntry(it.name, it.type, it.size), it.path, remoteDir, depth = 0) }
    return walk.plan
}

/**
 * Walks a local tree entry, filling the plan it carries (mirrors [DownloadWalk]). A file becomes an upload task;
 * a directory adds a remote subdirectory and recurses ([localBrowser] lists the local FS);
 * symlinks/other are skipped. [depth] is bounded by the same [TreeWalkLimit]: a local loop is a bind
 * mount away, and the plan is built before a byte moves either way. Remote paths are rebuilt from
 * [remoteDir] + a validated name; local paths come from the trusted local listing.
 */
private class UploadWalk(private val localBrowser: FileBrowser) {
    val plan = TransferPlan<UploadTask>()
    private val limit = TreeWalkLimit()

    suspend fun walk(entry: WalkEntry, localPath: String, remoteDir: String, depth: Int) {
        if (isUnsafeListingName(entry.name)) {
            throw FileBrowserException(FileBrowserFailure.IllegalName, detail = entry.name)
        }
        val remotePath = childPath(remoteDir, entry.name)
        when (entry.type) {
            FileItemType.File -> {
                limit.count()
                plan.files += UploadTask(entry.name, localPath, remotePath, entry.size)
            }
            FileItemType.Directory -> {
                limit.count()
                limit.descend(depth + 1)
                plan.dirs += remotePath
                localBrowser.list(localPath).forEach { child ->
                    walk(WalkEntry(child.name, child.type, child.size), child.path, remotePath, depth + 1)
                }
            }
            FileItemType.Symlink, FileItemType.Other -> Unit
        }
    }
}

/**
 * Creates directory [path] in [browser] (local or remote) if missing. `mkdir` without `-p` throws
 * on an already-existing directory, which is normal on a repeat transfer: a [FileContentBrowser.stat]
 * confirms a directory is what is already there before the error is ignored; otherwise (no
 * permission, or it is a file) the original mkdir error is rethrown.
 *
 * `stat` rather than a listing: the question is whether one path exists, and listing it to find out
 * would pull a whole directory over the wire — as many entries as the server cares to answer with —
 * once per directory in the plan.
 *
 * A symlink is the one case that still costs a listing. `stat` reports the link, not what it points
 * at (SFTP's is `SSH_FXP_LSTAT`, the local one is a no-follow metadata read), and a symlinked
 * destination directory is ordinary — so the walk that a plain `stat` cannot answer for is finished
 * by opening it, which follows the link on both sources. A link to a file fails there, as it should.
 */
internal suspend fun ensureDir(browser: FileContentBrowser, path: String) {
    try {
        browser.mkdir(path)
    } catch (e: FileBrowserException) {
        val existing = try {
            browser.stat(path)
        } catch (_: FileBrowserException) {
            null
        }
        when (existing?.type) {
            FileItemType.Directory -> Unit
            FileItemType.Symlink -> try {
                browser.list(path)
            } catch (_: FileBrowserException) {
                throw e
            }
            else -> throw e
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
