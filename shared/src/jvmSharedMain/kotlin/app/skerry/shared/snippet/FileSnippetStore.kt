package app.skerry.shared.snippet

import app.skerry.shared.io.PrivateConfig
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Файловое [SnippetStore]: сниппеты хранятся одним JSON-массивом [Snippet]. Зеркало
 * [app.skerry.shared.tunnel.FileTunnelStore] — upsert/remove с полной перезаписью файла из in-memory
 * кеша, приватная атомарная запись ([PrivateConfig.atomicWrite], 0600), битый/нечитаемый файл при
 * загрузке трактуется как пустой стор. Файл может содержать инлайн-креды команд — права важны.
 * Методы синхронизированы.
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
        // Files.writeString/readString требуют Android API 33+; пишем байтами (UTF-8) ради minSdk 26.
        PrivateConfig.atomicWrite(path, json.encodeToString(entries.toList()).toByteArray())
    }

    private fun load() {
        if (!Files.exists(path)) return
        PrivateConfig.harden(path) // апгрейд старого мир-читаемого файла при первом чтении
        runCatching { json.decodeFromString<List<Snippet>>(Files.readAllBytes(path).decodeToString()) }
            .onSuccess { entries += it }
    }
}
