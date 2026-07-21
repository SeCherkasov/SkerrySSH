package app.skerry.shared.sftp

/**
 * SFTP over an established SSH session. Opened via [app.skerry.shared.ssh.SshConnection.openSftp];
 * platform implementation is sshj on desktop ([app.skerry.shared.sftp] desktopMain).
 *
 * Covers directories ([list]/[mkdir]/[rmdir]), metadata ([stat]/[realpath]), whole-file
 * read/write for small files ([read]/[write]), path operations ([rename]/[remove]), and streamed
 * transfer between server and local filesystem ([download]/[upload]) without loading the whole
 * file into memory, with a progress callback. Resuming a partial transfer is not supported. All
 * methods are suspend: I/O runs off the calling thread.
 *
 * Paths are interpreted by the server (POSIX semantics, `/` separator). Relative paths and `~`
 * are expanded by the server; [realpath] canonicalizes a path (including the start directory via `.`).
 */
interface SftpClient {

    /**
     * Contents of directory [path], excluding `.` and `..`. Order is server-supplied (not sorted).
     * @throws SftpException path doesn't exist, isn't a directory, or lacks permission
     */
    suspend fun list(path: String): List<SftpEntry>

    /** Metadata for one object, or `null` if [path] doesn't exist. */
    suspend fun stat(path: String): SftpEntry?

    /**
     * Canonical absolute path for [path] (resolves `.`, `..`, relative paths).
     * Passing `.` returns the session's start working directory.
     * @throws SftpException path can't be resolved
     */
    suspend fun realpath(path: String): String

    /**
     * Reads file [path] entirely into memory (small files only), refusing anything over [maxBytes].
     * The limit is enforced while streaming, not just against the size the server reports: a server
     * is free to understate the size (or report `0`, as special files do) and then keep sending.
     * @throws SftpException path doesn't exist, is a directory, lacks permission, or exceeds [maxBytes]
     */
    suspend fun read(path: String, maxBytes: Long = SFTP_MAX_READ_BYTES): ByteArray

    /**
     * Writes [data] to file [path], creating or truncating it. The parent directory must exist.
     * @throws SftpException no permission, missing parent, or [path] is a directory
     */
    suspend fun write(path: String, data: ByteArray)

    /**
     * Streams remote file [remotePath] to local path [localPath] without loading it entirely into
     * memory (unlike [read]). The local file is created/overwritten. [onProgress] is called with
     * (transferred bytes, total bytes) as the transfer proceeds; total is the remote file's
     * reported size (`0` if the server didn't report one). The callback may fire from an IO
     * thread; switching to the UI thread is the caller's responsibility.
     * @throws SftpException file missing, is a directory, no permission, or channel/local I/O failure
     */
    suspend fun download(
        remotePath: String,
        localPath: String,
        onProgress: SftpProgress = SftpProgress { _, _ -> },
    )

    /**
     * Streams local file [localPath] to remote path [remotePath], creating/overwriting it.
     * [onProgress] is called with (transferred bytes, total bytes), where total is the local
     * file's size. The parent directory on the server must exist.
     * @throws SftpException local file missing, no permission/parent on the server, or channel failure
     */
    suspend fun upload(
        localPath: String,
        remotePath: String,
        onProgress: SftpProgress = SftpProgress { _, _ -> },
    )

    /**
     * Creates directory [path]. The parent must already exist (no `-p`).
     * @throws SftpException path already exists or lacks permission
     */
    suspend fun mkdir(path: String)

    /**
     * Removes file (not directory) [path].
     * @throws SftpException path doesn't exist, is a directory, or lacks permission
     */
    suspend fun remove(path: String)

    /**
     * Removes empty directory [path].
     * @throws SftpException directory not empty, doesn't exist, or lacks permission
     */
    suspend fun rmdir(path: String)

    /**
     * Renames/moves [from] to [to] on the server.
     * @throws SftpException source missing, target exists, or no permission
     */
    suspend fun rename(from: String, to: String)

    /** Closes the SFTP session (channel). Idempotent. The SSH connection stays open. */
    suspend fun close()
}

/**
 * Progress callback for streamed transfers ([SftpClient.download]/[SftpClient.upload]).
 * [transferred] is the cumulative bytes transferred, [total] is the full size (`0` if unknown).
 * Called repeatedly during the transfer; may fire from an IO thread.
 */
fun interface SftpProgress {
    fun onProgress(transferred: Long, total: Long)
}

/** Object type in an SFTP listing; `Other` covers devices, sockets, FIFOs, etc. */
enum class SftpEntryType { File, Directory, Symlink, Other }

/**
 * Metadata for an SFTP object. [path] is the path passed in or resolved during listing; [size] in
 * bytes; [modifiedEpochSeconds] is mtime (Unix seconds); [permissions] are POSIX mode bits (as
 * `st_mode & 0o7777`) for UI display. For a symlink, attributes describe the link itself, not its target.
 */
data class SftpEntry(
    val name: String,
    val path: String,
    val type: SftpEntryType,
    val size: Long,
    val modifiedEpochSeconds: Long,
    val permissions: Int,
)

/**
 * Default whole-file read cap ([SftpClient.read]), 64 MiB: a backstop against reading a huge file
 * into memory when the caller doesn't set its own, smaller limit.
 */
const val SFTP_MAX_READ_BYTES: Long = 64L * 1024 * 1024

/** SFTP operation error: missing path/permission, wrong object type, or channel failure. */
class SftpException(message: String, cause: Throwable? = null) : Exception(message, cause)
