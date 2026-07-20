package app.skerry.shared.vault

import okio.FileSystem
import okio.Path

/**
 * File-backed [BioArtifactStore] over okio — one implementation for desktop/Android (like
 * [FileVault]): I/O behind [FileSystem] (`FileSystem.SYSTEM` in prod, `FakeFileSystem` in tests).
 * The file is rewritten atomically (tmp + [FileSystem.atomicMove]); a corrupt/missing file on
 * [read] returns `null`, not a throw (biometrics being enabled must not break startup). Stored
 * next to `vault.json` as `vault.bio`; the only key material in it is the `wrap_bioKey(dataKey)`
 * wrapper, useless without the device's `bioKey`.
 *
 * [harden] is the platform hook for private permissions (0600 on POSIX), called on the tmp file
 * before it replaces the target (see [atomicWriteUtf8]): the key wrapper must not be world-readable
 * under a shared home directory. Defaults to no-op (tests; Android's filesDir is private to the
 * UID); desktop passes `PrivateConfig.harden`.
 */
class FileBioArtifactStore(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val harden: (Path) -> Unit = {},
) : BioArtifactStore {

    // Atomic write with tmp cleanup on failure (no orphaned wrapper) and harden applied to tmp.
    private val file = JsonFileStore(path, fileSystem, BioArtifact.serializer(), harden)

    override fun exists(): Boolean = file.exists()

    override fun read(): BioArtifact? = file.read()

    override fun write(artifact: BioArtifact) = file.write(artifact)

    override fun clear() = file.clear()
}
