package app.skerry.shared.vault

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Файловый [BioArtifactStore] на okio — один код для desktop/Android (как [FileVault]): I/O за
 * [FileSystem] (`FileSystem.SYSTEM` в проде, `FakeFileSystem` в тестах). Файл переписывается целиком
 * атомарно (tmp + [FileSystem.atomicMove]); битый/отсутствующий файл при [read] — `null`, не throw
 * (включённость биометрии не должна валить запуск). Кладётся рядом с `vault.json` под именем
 * `vault.bio`; ключевой материал в нём — только обёртка `wrap_bioKey(dataKey)`, бесполезная без
 * `bioKey` устройства.
 *
 * [harden] — платформенный хук приватных прав (0600 на POSIX), зовётся на tmp до подмены цели
 * (см. [atomicWriteUtf8]): обёртка ключа не должна быть мир-читаемой под общим домашним каталогом.
 * По умолчанию no-op (тесты; Android — filesDir приватен для UID); desktop передаёт
 * `PrivateConfig.harden`.
 */
class FileBioArtifactStore(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val harden: (Path) -> Unit = {},
) : BioArtifactStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun exists(): Boolean = fileSystem.exists(path)

    override fun read(): BioArtifact? =
        runCatching { json.decodeFromString<BioArtifact>(fileSystem.read(path) { readUtf8() }) }.getOrNull()

    override fun write(artifact: BioArtifact) {
        // Атомарно, с подчисткой tmp при провале (не оставлять осиротевшую обёртку) и harden на tmp.
        atomicWriteUtf8(fileSystem, path, json.encodeToString(artifact), harden)
    }

    override fun clear() {
        fileSystem.delete(path, mustExist = false)
    }
}
