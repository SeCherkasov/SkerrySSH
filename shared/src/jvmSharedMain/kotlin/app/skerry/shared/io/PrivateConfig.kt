package app.skerry.shared.io

import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Helpers for Skerry private config files: directory set to 0700, files to 0600 so local data
 * (inline snippet creds, host profiles, known-hosts) is not world-readable. No-op on non-POSIX
 * filesystems (Windows), where the user profile ACL applies; permission failures never fail the write.
 */
object PrivateConfig {

    private val DIR_PERMS_SET = PosixFilePermissions.fromString("rwx------")
    private val DIR_PERMS = PosixFilePermissions.asFileAttribute(DIR_PERMS_SET)
    private val FILE_PERMS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val FILE_ATTR = PosixFilePermissions.asFileAttribute(FILE_PERMS)

    /**
     * Ensures [dir] exists with 0700 perms. Created with parents when missing; an existing directory
     * is still forced to 0700 (upgrades installs where umask left it 0755).
     */
    fun ensureDir(dir: Path) {
        if (Files.exists(dir)) {
            runCatching { Files.setPosixFilePermissions(dir, DIR_PERMS_SET) }
            return
        }
        // file attribute is unsupported on non-POSIX filesystems; fall back to creating without it.
        runCatching { Files.createDirectories(dir, DIR_PERMS) }
            .onFailure { runCatching { Files.createDirectories(dir) } }
    }

    /** Sets file perms to 0600 (best-effort; no-op on non-POSIX filesystems). */
    fun harden(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, FILE_PERMS) }
    }

    /**
     * Writes [bytes] to [path] as a private file (0600) **without touching the parent directory** —
     * the difference from [atomicWrite], whose target is always Skerry's own config dir. This one
     * writes where a Save-As dialog pointed: Downloads, a USB stick, a shared project folder.
     * Narrowing that directory to 0700 as a side effect of exporting one key would be Skerry
     * re-permissioning the user's filesystem.
     *
     * Written through a private temp file and moved into place, so the bytes never sit at the umask
     * default even briefly, a failed write never leaves a truncated key where the user will later
     * mistake it for a backup, and a file pre-created at this (predictable) name by someone else is
     * replaced rather than written into. Permissions are best-effort on filesystems that have none
     * (Windows, FAT), where the profile ACL or the mount's mask applies instead. Failures are
     * rethrown — the caller decides whether to report them.
     */
    fun writePrivateFile(path: Path, bytes: ByteArray) = writeThroughTemp(path, bytes)

    /**
     * Atomically writes [bytes] to [path] as a private file (0600), creating the parent directory
     * (0700) if it is missing — the difference from [writePrivateFile], which must not touch a
     * directory the user chose. Both share [writeThroughTemp]: a unique adjacent temp file created
     * 0600, then moved into place. Failures are rethrown and the partial temp is cleaned up.
     */
    fun atomicWrite(path: Path, bytes: ByteArray) {
        path.parent?.let { ensureDir(it) }
        writeThroughTemp(path, bytes)
    }

    /**
     * Writes [bytes] into a private temp file beside [path] and moves it into place. Shared by
     * [atomicWrite] and [writePrivateFile]; the only difference between the two is whether the
     * parent directory is created and hardened first.
     *
     * The move is what makes every failure mode safe: a write that dies half-way leaves the temp
     * file (deleted here), never a truncated target, and the target is *replaced* rather than opened
     * — so a file someone else pre-created at the predictable export name, or a symlink planted
     * there, is swapped out for our own 0600 file instead of being written into or followed.
     */
    private fun writeThroughTemp(path: Path, bytes: ByteArray) {
        val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
        // A unique name (not a fixed `.tmp`) avoids a race between two processes over the same temp.
        val tmp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            // Created and opened in one step, with CREATE_NEW: `createTempFile` followed by a write
            // *by name* leaves a window in which someone who can write to this directory — it is the
            // one the user picked in Save-As, not ours — swaps the temp for their own file or a
            // symlink and receives the private key. O_EXCL closes it.
            val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val channel =
                if (posix) Files.newByteChannel(tmp, options, FILE_ATTR) else Files.newByteChannel(tmp, options)
            // Channels.newOutputStream loops until everything is written; a bare channel write may be
            // short (an ENOSPC boundary, a FUSE or network mount) and return normally, leaving a
            // truncated secret behind.
            channel.use { sink ->
                Channels.newOutputStream(sink).use { it.write(bytes) }
                // Flushed before the rename: without it the move can land while the data does not
                // (ext4 data=writeback, most network mounts), leaving a zero-length file where a
                // host-key mismatch record or a private key was supposed to be. Best-effort — a
                // filesystem may reject force(), and only a FileChannel offers it at all.
                runCatching { (sink as? FileChannel)?.force(true) }
            }
            // No-op where the create attribute already applied, and a no-op on non-POSIX too — there
            // the mount's mask or the profile ACL is what decides, as the KDoc says.
            harden(tmp)
            runCatching { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }
}
