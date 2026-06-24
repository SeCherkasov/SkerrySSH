package app.skerry.shared.tunnel

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Файловое [TunnelStore]: туннели хранятся одним JSON-массивом [Tunnel]. Зеркало
 * [app.skerry.shared.host.FileHostStore] — upsert/remove с полной перезаписью файла из in-memory
 * кеша, атомарная запись (временный файл рядом + ATOMIC_MOVE), битый/нечитаемый файл при загрузке
 * трактуется как пустой стор. Методы синхронизированы.
 */
class FileTunnelStore(private val path: Path) : TunnelStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entries = mutableListOf<Tunnel>()

    init {
        load()
    }

    @Synchronized
    override fun all(): List<Tunnel> = entries.toList()

    @Synchronized
    override fun put(tunnel: Tunnel) {
        val index = entries.indexOfFirst { it.id == tunnel.id }
        if (index >= 0) entries[index] = tunnel else entries += tunnel
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
        runCatching { json.decodeFromString<List<Tunnel>>(Files.readAllBytes(path).decodeToString()) }
            .onSuccess { entries += it }
    }
}
