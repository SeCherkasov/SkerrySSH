package app.skerry.shared.sftp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.xfer.TransferListener

/**
 * Desktop [SftpClient] implementation over sshj `SFTPClient` (one SFTP channel per instance).
 * Every operation runs on [Dispatchers.IO] since the sshj API is blocking. Protocol errors
 * (`SFTPException`) and disconnects (`IOException`) are wrapped in [SftpException]; the one
 * exception is [stat], where "no such file" is `null`, not an error.
 *
 * [maxReadBytes] is the channel's own ceiling for [read]; the effective limit is the smaller of it
 * and the caller's `maxBytes`. It is enforced both against the reported size (so an honestly-large
 * file isn't fetched at all) and while streaming (so a server understating the size — `0` for
 * `/dev/zero` and friends — can't grow the buffer without bound).
 */
internal class SshjSftpClient(
    private val sftp: SFTPClient,
    private val maxReadBytes: Long = DEFAULT_MAX_READ_BYTES,
) : SftpClient {

    private val closed = AtomicBoolean(false)

    override suspend fun list(path: String): List<SftpEntry> = io("Failed to read directory $path") {
        // sshj filters . and .. out of the listing itself.
        sftp.ls(path).map { it.toEntry() }
    }

    override suspend fun stat(path: String): SftpEntry? = withContext(Dispatchers.IO) {
        if (closed.get()) throw SftpException("SFTP channel closed")
        try {
            // lstat, not stat: don't follow symlinks — attributes of the link itself, consistent with list().
            sftp.lstat(path).toEntry(path)
        } catch (e: SFTPException) {
            // "No such file" maps to null. Servers differ: OpenSSH/embedded may return NO_SUCH_PATH
            // instead of NO_SUCH_FILE when a path component is missing — map both.
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                e.statusCode == Response.StatusCode.NO_SUCH_PATH
            ) {
                null
            } else {
                throw SftpException("Failed to get metadata for $path", e)
            }
        } catch (e: IOException) {
            throw SftpException("Failed to get metadata for $path", e)
        }
    }

    override suspend fun realpath(path: String): String = io("Failed to resolve path $path") {
        sftp.canonicalize(path)
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = io("Failed to read file $path") {
        val cap = minOf(maxBytes, maxReadBytes)
        sftp.open(path).use { file ->
            val size = file.length()
            if (size > cap) {
                throw SftpException("File $path is too large to read whole: $size B (limit $cap B)")
            }
            file.RemoteFileInputStream().use { readAtMost(it, cap, path) }
        }
    }

    override suspend fun write(path: String, data: ByteArray): Unit = io("Failed to write file $path") {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            file.RemoteFileOutputStream().use { it.write(data) }
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        onProgress: SftpProgress,
    ): Unit = io("Failed to download file $remotePath") {
        // sshj.get streams the file to disk; progress comes from the transfer channel's TransferListener.
        withTransferListener(onProgress) { sftp.get(remotePath, localPath) }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        onProgress: SftpProgress,
    ): Unit = io("Failed to upload file to $remotePath") {
        withTransferListener(onProgress) { sftp.put(localPath, remotePath) }
    }

    override suspend fun mkdir(path: String): Unit = io("Failed to create directory $path") {
        sftp.mkdir(path)
    }

    override suspend fun remove(path: String): Unit = io("Failed to remove file $path") {
        sftp.rm(path)
    }

    override suspend fun rmdir(path: String): Unit = io("Failed to remove directory $path") {
        sftp.rmdir(path)
    }

    override suspend fun rename(from: String, to: String): Unit = io("Failed to rename $from -> $to") {
        sftp.rename(from, to)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        // Idempotent: a repeat close() must not touch the channel again.
        if (!closed.compareAndSet(false, true)) return@withContext
        runCatching { sftp.close() }
        Unit
    }

    /**
     * Run a blocking sshj operation, wrapping its errors as [SftpException]. Rejects use after
     * [close] up front — otherwise sshj would throw an opaque engine IOException instead.
     */
    private inline fun <T> ioBody(message: String, block: () -> T): T {
        if (closed.get()) throw SftpException("SFTP channel closed")
        return try {
            block()
        } catch (e: IOException) {
            // Include the sshj cause in the text: otherwise the UI shows only the wrapper message
            // ("Failed to upload file...") and the real reason (no space/permissions, channel drop) is lost.
            val cause = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName
            throw SftpException(if (cause != null) "$message: $cause" else message, e)
        }
    }

    private suspend inline fun <T> io(message: String, crossinline block: () -> T): T =
        withContext(Dispatchers.IO) { ioBody(message, block) }

    /**
     * Set a progress listener on sshj's shared transfer channel for the duration of [block] and
     * clear it after — so the previous transfer's listener doesn't linger on the shared
     * `fileTransfer` (operations are serialized higher up the stack, but the channel's global
     * state is best not left dirty).
     */
    private inline fun <T> withTransferListener(onProgress: SftpProgress, block: () -> T): T {
        sftp.fileTransfer.transferListener = progressListener(onProgress)
        try {
            return block()
        } finally {
            sftp.fileTransfer.transferListener = progressListener(SftpProgress { _, _ -> })
        }
    }

    /**
     * Adapts sshj's progress hierarchy to [SftpProgress]. A hierarchical [TransferListener] for a
     * single file reduces to a byte listener: `file(name, size)` gives the full size, and its
     * `reportProgress` reports cumulative bytes transferred. A single file transfer has no nested
     * directories, so `directory` just returns itself.
     */
    private fun progressListener(onProgress: SftpProgress): TransferListener =
        object : TransferListener {
            override fun directory(name: String): TransferListener = this
            override fun file(name: String, size: Long): StreamCopier.Listener =
                StreamCopier.Listener { transferred -> onProgress.onProgress(transferred, size) }
        }

    private companion object {
        /** Default channel-level read cap for [read]; the shared contract's [SFTP_MAX_READ_BYTES]. */
        const val DEFAULT_MAX_READ_BYTES = SFTP_MAX_READ_BYTES
    }
}

/**
 * Reads [input] fully but never past [cap] bytes, so a source that understates its size (or streams
 * endlessly, as a special file does) can't grow the buffer without bound. Overshooting is an error,
 * not a truncation: silently returning a cut-off file would be corruption the moment it's saved back.
 */
internal fun readAtMost(input: InputStream, cap: Long, label: String): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > cap) throw SftpException("File $label is larger than the read limit of $cap B")
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

/** sshj listing entry to [SftpEntry] (name and path come from the server response). */
private fun RemoteResourceInfo.toEntry(): SftpEntry =
    attributes.toEntry(name = name, path = path)

/** Attributes of a single object to [SftpEntry]; name is derived from the tail of [path]. */
private fun FileAttributes.toEntry(path: String): SftpEntry =
    toEntry(name = path.substringAfterLast('/').ifEmpty { path }, path = path)

private fun FileAttributes.toEntry(name: String, path: String): SftpEntry =
    SftpEntry(
        name = name,
        path = path,
        type = type.toEntryType(),
        size = size,
        modifiedEpochSeconds = mtime,
        // Permission bits only (file-type bits excluded) — for the permissions UI.
        permissions = mode.permissionsMask,
    )

private fun FileMode.Type.toEntryType(): SftpEntryType = when (this) {
    FileMode.Type.REGULAR -> SftpEntryType.File
    FileMode.Type.DIRECTORY -> SftpEntryType.Directory
    FileMode.Type.SYMLINK -> SftpEntryType.Symlink
    else -> SftpEntryType.Other
}
