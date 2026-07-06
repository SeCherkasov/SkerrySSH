package app.skerry.shared.ai.local

import okio.FileSystem
import okio.Path

/**
 * Disk storage for downloaded GGUF models: file layout under [dir] and install-state checks. An
 * in-progress download lives alongside its target as `<file>.part` (resumed via Range, see
 * [ModelDownloader]); a model counts as installed only when its size exactly matches the catalog —
 * a truncated/foreign file won't pass (sha256 integrity is guaranteed by the downloader before the
 * final rename).
 *
 * Models are public weights, not secrets: no 0600/hardened permissions needed (the directory is
 * already private: `filesDir` on Android, `~/.local/share/skerry` on desktop).
 */
class LocalModelStore(
    private val fileSystem: FileSystem,
    private val dir: Path,
) {
    /** Path of the installed model. */
    fun path(model: LocalModel): Path = dir.resolve(model.fileName)

    /** Path of an in-progress download (resumed from here). */
    fun partPath(model: LocalModel): Path = dir.resolve("${model.fileName}.part")

    /** Whether the model is installed: file present and size matches the catalog byte-for-byte. */
    fun isInstalled(model: LocalModel): Boolean =
        fileSystem.metadataOrNull(path(model))?.size == model.sizeBytes

    /** Bytes downloaded so far into the part file; 0 if the download hasn't started. */
    fun downloadedBytes(model: LocalModel): Long =
        fileSystem.metadataOrNull(partPath(model))?.size ?: 0L

    /** Deletes the model and its in-progress download (idempotent). */
    fun delete(model: LocalModel) {
        fileSystem.delete(path(model), mustExist = false)
        fileSystem.delete(partPath(model), mustExist = false)
    }
}
