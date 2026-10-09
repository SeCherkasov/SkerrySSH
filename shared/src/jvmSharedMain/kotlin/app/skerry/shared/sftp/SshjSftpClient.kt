package app.skerry.shared.sftp

import com.hierynomus.sshj.sftp.RemoteResourceSelector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.xfer.FilePermission
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.common.StreamCopier

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

    /**
     * `sftp.ls` accumulates `SSH_FXP_NAME` responses until the server says there are no more, so
     * without a selector how long that list grows is the server's decision: one `ls` answered with
     * tens of millions of names exhausts the heap during an ordinary browse, before any guard above
     * this call gets to run. [listingSelector] bounds the accumulated list — one entry past [limit],
     * enough for the caller to see the listing was cut short, and no more than that held.
     *
     * The list, not the packet. sshj reads a whole SFTP reply into its own buffer before a single
     * entry is parsed, and that buffer is capped by sshj at 1 GiB and then kept for the life of the
     * channel; nothing on this side of the API can bound it. What is bounded here is everything
     * built from those packets, which is what grows without limit as the server keeps answering.
     */
    override suspend fun list(path: String, limit: Int): List<SftpEntry> = io("Failed to read directory $path") {
        // sshj filters . and .. out of the listing itself.
        sftp.ls(path, listingSelector(path, limit)).map { it.toEntry() }
    }

    override suspend fun stat(path: String): SftpEntry? = io("Failed to get metadata for $path") {
        try {
            // lstat, not stat: don't follow symlinks — consistent with list().
            sftp.lstat(path).toEntry(path)
        } catch (e: SFTPException) {
            // Servers differ on a missing path component: accept both missing-object codes.
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                e.statusCode == Response.StatusCode.NO_SUCH_PATH
            ) null else throw e
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
    ): Unit = io("Failed to download file $remotePath") { context ->
        val target = Paths.get(localPath)
        // sshj would write into a directory given as the target; a caller always names the file.
        if (Files.isDirectory(target)) throw IOException("$localPath is a directory")
        val staging = target.resolveSibling(stagingName(target.fileName.toString()))
        val metadataStarted = System.nanoTime()
        val remote = sftp.stat(remotePath)
        val requests = transferRequests(metadataStarted)
        var placed = false
        try {
            // Owner-only while the bytes arrive; the final mode is set once they are all there.
            createOwnerOnly(staging)
            if (remote.type != FileMode.Type.REGULAR && remote.type != FileMode.Type.UNKNOWN) {
                throw IOException("$remotePath is not a regular file")
            }
            sftp.open(remotePath).use { file ->
                file.ReadAheadRemoteFileInputStream(requests, 0, remote.size).use { input ->
                    Files.newOutputStream(staging).use { output ->
                        copyTransfer(input, output, TRANSFER_BUFFER_BYTES, context) { transferred ->
                            onProgress.onProgress(transferred, remote.size)
                        }
                    }
                }
            }
            narrowToRemoteMode(staging, remote.mode.permissionsMask)
            Files.setLastModifiedTime(staging, FileTime.from(remote.mtime, TimeUnit.SECONDS))
            context.ensureActive()
            moveReplacing(staging, target)
            placed = true
        } finally {
            if (!placed) runCatching { Files.deleteIfExists(staging) }
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        onProgress: SftpProgress,
    ): Unit = io("Failed to upload file to $remotePath") { context ->
        // Written in place, as OpenSSH's sftp does: a symlink is written through, and the file keeps
        // its owner, group and mode. A failed transfer leaves the target truncated. No attributes
        // are sent after the write: sshj's would be 0644 for every file, whatever its local mode.
        val source = Paths.get(localPath)
        if (!Files.isRegularFile(source)) throw IOException("$localPath is not a regular file")
        Files.newInputStream(source).use { input ->
            val total = Files.size(source)
            // Keep sshj's directory-target behavior, but avoid its second redundant STAT.
            val metadataStarted = System.nanoTime()
            val destination = uploadDestination(source, remotePath)
            val requests = transferRequests(metadataStarted)
            sftp.open(destination, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                val bufferSize = minOf(TRANSFER_BUFFER_BYTES,
                    sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead)
                if (bufferSize <= 0) throw IOException("SFTP packet size is too small for file data")
                // The stream drains and checks every WRITE status before the remote handle closes.
                file.RemoteFileOutputStream(0, requests).use { output ->
                    copyTransfer(input, output, bufferSize, context) { transferred ->
                        onProgress.onProgress(transferred, total)
                    }
                }
            }
        }
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
    private inline fun <T> ioBody(message: String, context: CoroutineContext, block: () -> T): T {
        if (closed.get()) throw SftpException("SFTP channel closed")
        return try {
            block()
        } catch (e: IOException) {
            // sshj wraps an interrupted Promise wait in IOException. Cancellation must not
            // escape as a network failure and cancel the caller's parent session.
            context.ensureActive()
            // Include the sshj cause in the text: otherwise the UI shows only the wrapper message
            // ("Failed to upload file...") and the real reason (no space/permissions, channel drop) is lost.
            val cause = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName
            throw SftpException(if (cause != null) "$message: $cause" else message, e)
        }
    }

    private suspend inline fun <T> io(message: String, crossinline block: (CoroutineContext) -> T): T {
        // sshj's Promise waits preserve the interrupt flag when wrapping InterruptedException.
        // runInterruptible breaks those waits and clears its interrupt before releasing the IO
        // thread. The SFTP channel and SSH session remain available for subsequent operations.
        val context = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) { ioBody(message, context) { block(context) } }
    }

    /** Bounded packet size and request window; progress belongs to this transfer, not the channel. */
    private fun copyTransfer(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int,
        context: CoroutineContext,
        onProgress: (Long) -> Unit,
    ) {
        StreamCopier(input, output, sftp.sftpEngine.subsystem.getLoggerFactory())
            .bufSize(bufferSize)
            .keepFlushing(false)
            .listener { transferred ->
                context.ensureActive()
                onProgress(transferred)
            }
            .copy()
        context.ensureActive()
    }

    private fun uploadDestination(source: Path, remotePath: String): String {
        val remote = try {
            sftp.stat(remotePath)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                e.statusCode == Response.StatusCode.NO_SUCH_PATH
            ) return remotePath else throw e
        }
        return if (remote.type == FileMode.Type.DIRECTORY) {
            "${remotePath.trimEnd('/')}/${source.fileName}"
        } else remotePath
    }

    private fun transferRequests(metadataStarted: Long): Int =
        if (System.nanoTime() - metadataStarted >= HIGH_LATENCY_NANOS) HIGH_LATENCY_REQUESTS else DEFAULT_REQUESTS

    private companion object {
        /** Default channel-level read cap for [read]; the shared contract's [SFTP_MAX_READ_BYTES]. */
        const val DEFAULT_MAX_READ_BYTES = SFTP_MAX_READ_BYTES

        // sshj's get/put hardcode 16. A 64-request window fills a high-latency link without growing
        // requested packet payloads. sshj may queue up to two extra requests: under 2.1 MiB of
        // requested read-ahead data at 32 KiB per packet.
        // Reuse the required STAT as a latency sample: larger queues cost throughput on fast links.
        const val DEFAULT_REQUESTS = 16
        const val HIGH_LATENCY_REQUESTS = 64
        const val HIGH_LATENCY_NANOS = 25_000_000L
        const val TRANSFER_BUFFER_BYTES = 32 * 1024
    }
}

/**
 * Name a download is written under beside its target, then renamed over it once complete: a transfer
 * that fails half-way must neither leave a truncated file under the name the user chose nor cost them
 * the file it was replacing. Hidden, and short enough to stay a legal name whatever [name] is.
 */
private fun stagingName(name: String): String =
    ".${name.take(STAGING_NAME_KEEP)}.${Random.nextLong().toULong().toString(16)}.skerry-part"

private const val STAGING_NAME_KEEP = 200

/** Creates [path] readable and writable by its owner only, where the filesystem has POSIX modes. */
private fun createOwnerOnly(path: Path) {
    if (!path.isPosix()) return
    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
}

/**
 * The remote mode, narrowed: never wider than the server's (a 0600 key stays 0600), never writable by
 * group or others (0666 would make a local file world-writable), and always readable and writable by
 * the owner (0400 would lock the user out of what they just downloaded).
 */
private fun narrowToRemoteMode(path: Path, remoteMode: Int) {
    if (!path.isPosix()) return
    Files.setPosixFilePermissions(path, FilePermission.fromMask((remoteMode and NARROW_MASK) or OWNER_RW).toPosix())
}

private fun Path.isPosix() = "posix" in fileSystem.supportedFileAttributeViews()

private fun Set<FilePermission>.toPosix(): Set<PosixFilePermission> = mapNotNullTo(mutableSetOf()) {
    when (it) {
        FilePermission.USR_R -> PosixFilePermission.OWNER_READ
        FilePermission.USR_W -> PosixFilePermission.OWNER_WRITE
        FilePermission.USR_X -> PosixFilePermission.OWNER_EXECUTE
        FilePermission.GRP_R -> PosixFilePermission.GROUP_READ
        FilePermission.GRP_X -> PosixFilePermission.GROUP_EXECUTE
        FilePermission.OTH_R -> PosixFilePermission.OTHERS_READ
        FilePermission.OTH_X -> PosixFilePermission.OTHERS_EXECUTE
        else -> null
    }
}

private const val NARROW_MASK = 0b111_101_101 // 0755
private const val OWNER_RW = 0b110_000_000 // 0600

/** Moves [source] over [target], atomically where the filesystem can. */
private fun moveReplacing(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Longest single path component any of the filesystems behind an SFTP server can name. */
private const val NAME_MAX = 255

/** Longest whole path those same filesystems can name. */
private const val PATH_MAX = 4096

/**
 * The selector every entry of a listing of [path] goes through: at most `limit + 1` entries taken,
 * and an entry no filesystem could have produced refused outright.
 *
 * How many entries is only half of a bound. One SSH string may be 32 KB, so a hundred thousand of
 * them is three orders of magnitude more memory than the count suggests. [NAME_MAX] and [PATH_MAX]
 * are POSIX's own limits, so no real server trips them; one that does is describing itself, not a
 * file the user might want, and the listing is refused rather than drawn short. What is left is the
 * honest residual: `limit` times a name a filesystem can hold, which is the number the caller's cap
 * was chosen against.
 *
 * One selector per listing — it counts.
 */
internal fun listingSelector(path: String, limit: Int): RemoteResourceSelector {
    // Long, so a caller asking for Int.MAX_VALUE needs no special case: the ceiling is one past the
    // limit whatever the limit is, and a special case is a branch nothing exercises.
    var taken = 0L
    val ceiling = limit.toLong() + 1
    return RemoteResourceSelector { info ->
        if (info.name.length > NAME_MAX || info.path.length > PATH_MAX) {
            throw SftpException("Listing $path returned an entry no filesystem can name")
        }
        if (taken++ < ceiling) RemoteResourceSelector.Result.ACCEPT else RemoteResourceSelector.Result.BREAK
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
