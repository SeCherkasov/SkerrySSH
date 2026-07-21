package app.skerry.shared.vault

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * One small JSON document on disk, read/written the way the vault directory needs it: atomically
 * (tmp + move, see [atomicWriteUtf8]) with [harden] applied to the tmp file before it lands, and
 * never throwing on read — a corrupt or missing file reads as `null`, because none of these
 * documents may keep the app from starting.
 *
 * Shared by [FileBioArtifactStore] and [FileBiometricSupportStore]; the vault file itself is not one
 * of these (its payload is encrypted, not JSON-in-the-clear).
 */
internal class JsonFileStore<T : Any>(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val serializer: KSerializer<T>,
    private val harden: (Path) -> Unit = {},
    // encodeDefaults: a field left at its default must still be written, or re-reading it would fall
    // back to the default and silently change meaning (see BiometricSupportVerdict.unsupported).
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true },
) {

    fun exists(): Boolean = fileSystem.exists(path)

    fun read(): T? =
        runCatching { json.decodeFromString(serializer, fileSystem.read(path) { readUtf8() }) }.getOrNull()

    fun write(value: T) {
        atomicWriteUtf8(fileSystem, path, json.encodeToString(serializer, value), harden)
    }

    fun clear() {
        fileSystem.delete(path, mustExist = false)
    }
}
