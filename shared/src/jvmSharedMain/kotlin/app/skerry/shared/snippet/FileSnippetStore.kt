package app.skerry.shared.snippet

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Файловое [SnippetStore]: сниппеты хранятся одним JSON-массивом [Snippet]. Зеркало
 * [app.skerry.shared.tunnel.FileTunnelStore] — upsert/remove с полной перезаписью файла из in-memory
 * кеша, атомарная запись (временный файл рядом + ATOMIC_MOVE), битый/нечитаемый файл при загрузке
 * трактуется как пустой стор. Методы синхронизированы.
 */
class FileSnippetStore(private val path: Path) : SnippetStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entries = mutableListOf<Snippet>()

    init {
        load()
    }

    @Synchronized
    override fun all(): List<Snippet> = entries.toList()

    @Synchronized
    override fun put(snippet: Snippet) {
        val index = entries.indexOfFirst { it.id == snippet.id }
        if (index >= 0) entries[index] = snippet else entries += snippet
        persist()
    }

    @Synchronized
    override fun remove(id: String) {
        if (entries.removeAll { it.id == id }) persist()
    }

    private fun persist() {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        // Files.writeString/readString требуют Android API 33+; пишем байтами (UTF-8) ради minSdk 26.
        Files.write(tmp, json.encodeToString(entries.toList()).toByteArray())
        runCatching { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun load() {
        if (!Files.exists(path)) return
        runCatching { json.decodeFromString<List<Snippet>>(Files.readAllBytes(path).decodeToString()) }
            .onSuccess { entries += it }
    }
}
