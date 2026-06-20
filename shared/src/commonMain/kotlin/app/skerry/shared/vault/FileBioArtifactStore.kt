package app.skerry.shared.vault

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Файловый [BioArtifactStore] на okio — один код для desktop/Android (как [FileVault]): I/O за
 * [FileSystem] (`FileSystem.SYSTEM` в проде, `FakeFileSystem` в тестах). Файл переписывается целиком
 * атомарно (tmp + [FileSystem.atomicMove]); битый/отсутствующий файл при [read] — `null`, не throw
 * (включённость биометрии не должна валить запуск). Кладётся рядом с `vault.json` под именем
 * `vault.bio`; ключевой материал в нём — только обёртка `wrap_bioKey(dataKey)`, бесполезная без
 * `bioKey` устройства.
 */
class FileBioArtifactStore(
    private val path: Path,
    private val fileSystem: FileSystem,
) : BioArtifactStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun exists(): Boolean = fileSystem.exists(path)

    override fun read(): BioArtifact? =
        runCatching { json.decodeFromString<BioArtifact>(fileSystem.read(path) { readUtf8() }) }.getOrNull()

    override fun write(artifact: BioArtifact) {
        path.parent?.let { fileSystem.createDirectories(it) }
        val tmp = path.parent?.resolve("${path.name}.tmp") ?: "${path.name}.tmp".toPath()
        fileSystem.write(tmp) { writeUtf8(json.encodeToString(artifact)) }
        try {
            fileSystem.atomicMove(tmp, path)
        } catch (e: Throwable) {
            runCatching { fileSystem.delete(tmp, mustExist = false) } // не оставлять осиротевший tmp с обёрткой
            throw e
        }
    }

    override fun clear() {
        fileSystem.delete(path, mustExist = false)
    }
}
