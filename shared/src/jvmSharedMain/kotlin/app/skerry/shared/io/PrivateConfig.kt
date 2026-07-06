package app.skerry.shared.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Helpers for Skerry private config files: directory set to 0700, files to 0600 so local data
 * (inline snippet creds, host profiles, known-hosts) is not world-readable. No-op on non-POSIX
 * filesystems (Windows), where the user profile ACL applies; permission failures never fail the write.
 */
object PrivateConfig {

    private val DIR_PERMS_SET = PosixFilePermissions.fromString("rwx------")
    private val DIR_PERMS = PosixFilePermissions.asFileAttribute(DIR_PERMS_SET)
    private val FILE_PERMS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

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
     * Atomically writes [bytes] to [path] as a private file (0600). Writes to a unique adjacent temp
     * file (0600 on POSIX via `createTempFile`), then moves it into place ([ATOMIC_MOVE], falling back
     * to [REPLACE_EXISTING]). The unique name (not a fixed `.tmp`) avoids a race between two processes
     * over the same temp. Failures are rethrown and the partial temp is cleaned up.
     */
    fun atomicWrite(path: Path, bytes: ByteArray) {
        val parent = path.parent
        if (parent != null) ensureDir(parent)
        val tmp = if (parent != null) {
            Files.createTempFile(parent, "${path.fileName}.", ".tmp")
        } else {
            path.resolveSibling("${path.fileName}.tmp").also { Files.deleteIfExists(it) }
        }
        try {
            Files.write(tmp, bytes)
            harden(tmp) // createTempFile does not set 0600 on non-POSIX filesystems; enforce it (no-op on POSIX)
            runCatching { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }
}
