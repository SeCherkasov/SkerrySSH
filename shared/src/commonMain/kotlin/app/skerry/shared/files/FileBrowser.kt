package app.skerry.shared.files

/** Object type in the file panel; Other covers devices, sockets, FIFOs, etc. */
enum class FileItemType { File, Directory, Symlink, Other }

/**
 * File panel object metadata, source-neutral (local filesystem or remote SFTP) so the same UI list
 * and controller serve both panes of the dual-pane view. [path] is an absolute path in the source's
 * own namespace; [size] in bytes; [modifiedEpochSeconds] is mtime (Unix seconds, `0` if unreported);
 * [permissions] are POSIX mode bits (`st_mode & 0o7777`), or `null` when the source doesn't report
 * them (the local okio browser). For a symlink, attributes describe the link itself.
 */
data class FileItem(
    val name: String,
    val path: String,
    val type: FileItemType,
    val size: Long,
    val modifiedEpochSeconds: Long,
    val permissions: Int? = null,
)

/**
 * Navigation and directory operations for a single file source — shared contract for the local
 * filesystem ([LocalFileBrowser]) and remote SFTP (adapter over [app.skerry.shared.sftp.SftpClient]).
 * File transfer is out of scope: in dual-pane mode it always goes between local filesystem and SFTP
 * via `SftpClient.download`/`upload`, so it lives in the screen coordinator, not the browser. All
 * methods are suspend (I/O off the caller's thread). Paths are absolute, POSIX semantics (`/` separator).
 */
interface FileBrowser {

    /** Short source label for the panel header ("This Mac", host name). */
    val label: String

    /**
     * Canonical absolute path for [path] (resolves `.`, `..`). Pass `.` to get the source's starting
     * directory (home locally, session working directory for SFTP). Symlink resolution is not
     * guaranteed and depends on the source: SFTP resolves them server-side (`SSH_FXP_REALPATH`), the
     * local implementation only normalizes the path lexically.
     * @throws FileBrowserException if the path cannot be resolved
     */
    suspend fun realpath(path: String): String

    /**
     * Contents of directory [path], excluding `.` and `..`; order is not guaranteed (the panel sorts).
     * @throws FileBrowserException if the path doesn't exist, isn't a directory, or access is denied
     */
    suspend fun list(path: String): List<FileItem>

    /**
     * Create directory [path]. Parent must already exist (no `-p`).
     * @throws FileBrowserException if the path is occupied or access is denied
     */
    suspend fun mkdir(path: String)

    /**
     * Delete [item]. A directory is deleted recursively (with its contents); a file/symlink is
     * deleted as itself (a symlink's target is not followed). Confirmation is the UI's responsibility.
     * @throws FileBrowserException if the path is missing or access is denied
     */
    suspend fun delete(item: FileItem)

    /**
     * Rename/move [from] to [to] within the source.
     * @throws FileBrowserException if the source is missing, the target is occupied, or access is denied
     */
    suspend fun rename(from: String, to: String)
}

/**
 * A [FileBrowser] that can also read and write a whole file — what the built-in viewer/editor
 * (F3/F4) needs on top of navigation. Split from [FileBrowser] because the panel controller works
 * with navigation alone; both production sources (local FS and SFTP) implement this.
 */
interface FileContentBrowser : FileBrowser {

    /**
     * Metadata for one object, or `null` if [path] doesn't exist. Used to detect a file changed
     * underneath the editor (size/mtime) before overwriting it.
     * @throws FileBrowserException if the source can't be queried at all (connection/access)
     */
    suspend fun stat(path: String): FileItem?

    /**
     * Reads file [path] entirely into memory, refusing anything over [maxBytes]
     * ([FileBrowserFailure.TooLarge]). The cap is checked against the reported size *and* the bytes
     * actually read, so a source understating the size can't blow up memory.
     * @throws FileBrowserException if the path is missing, is a directory, access is denied, or the file is too large
     */
    suspend fun readFile(path: String, maxBytes: Long): ByteArray

    /**
     * Writes [data] to file [path], creating or truncating it in place (the parent must exist).
     * In place, not write-to-temp-and-rename: the inode survives, so permissions/owner/hard links
     * are preserved and SFTP servers that refuse `rename` onto an existing path still work.
     * @throws FileBrowserException if access is denied, the parent is missing, or [path] is a directory
     */
    suspend fun writeFile(path: String, data: ByteArray)
}

/**
 * Typed, user-facing reason for a [FileBrowserException]. The UI maps it to a localized string;
 * platform text (okio/sshj/OS) is never shown as the primary message.
 */
enum class FileBrowserFailure {
    /** Local filesystem error: missing path, denied access, wrong object type. */
    LocalIo,

    /** Remote SFTP error: missing path, denied access, protocol/connection failure. */
    Sftp,

    /** A listing entry carries a name that is unsafe to use as a path component. */
    IllegalName,

    /** The picked source file could not be opened for reading. */
    OpenSource,

    /** The chosen save target could not be opened for writing. */
    OpenTarget,

    /** The file is larger than the caller's cap (whole-file read for the viewer/editor). */
    TooLarge,
}

/**
 * File browser operation error. [failure] is the user-facing reason (localized by the UI);
 * [detail] carries raw platform text for logs and diagnostics only — never for the primary message.
 */
class FileBrowserException(
    val failure: FileBrowserFailure,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: failure.name, cause)
