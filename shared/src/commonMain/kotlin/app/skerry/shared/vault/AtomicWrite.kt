package app.skerry.shared.vault

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Atomic UTF-8 file rewrite, shared by [FileVault], [FileSecurityLog] and
 * [FileBioArtifactStore]: text is written to a temp file next to the target, then
 * [FileSystem.atomicMove] replaces the target (NIO `ATOMIC_MOVE+REPLACE_EXISTING`; legacy/native
 * POSIX `rename(2)`; `FakeFileSystem`). If the write or move fails, the exception propagates to
 * the caller (its state is untouched, since field commit happens after persist) and the tmp file
 * is cleaned up so no orphaned copy of secrets is left on disk.
 *
 * [harden] runs on the tmp file before the move: the secrets file gets its final permissions
 * (0600 on POSIX) before it replaces the target, so there is no umask-permission window. Defaults
 * to no-op (tests on `FakeFileSystem`, Android's filesDir is private to the UID); desktop passes
 * `PrivateConfig.harden`.
 */
internal fun atomicWriteUtf8(
    fileSystem: FileSystem,
    path: Path,
    text: String,
    harden: (Path) -> Unit = {},
) {
    path.parent?.let { fileSystem.createDirectories(it) }
    val tmp = path.parent?.resolve("${path.name}.tmp") ?: "${path.name}.tmp".toPath()
    try {
        fileSystem.write(tmp) { writeUtf8(text) }
        harden(tmp)
        fileSystem.atomicMove(tmp, path)
    } catch (e: Throwable) {
        runCatching { fileSystem.delete(tmp, mustExist = false) } // don't leave an orphaned tmp file
        throw e
    }
}
