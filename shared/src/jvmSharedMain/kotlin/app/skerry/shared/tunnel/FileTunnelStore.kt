package app.skerry.shared.tunnel

import app.skerry.shared.io.PrivateConfig
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Файловое [TunnelStore]: туннели хранятся одним JSON-массивом [Tunnel]. Зеркало
 * [app.skerry.shared.host.FileHostStore] — upsert/remove с полной перезаписью файла из in-memory
 * кеша, приватная атомарная запись ([PrivateConfig.atomicWrite], 0600), битый/нечитаемый файл при
 * загрузке трактуется как пустой стор. Методы синхронизированы.
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
        // Files.writeString/readString требуют Android API 33+; пишем байтами (UTF-8) ради minSdk 26.
        PrivateConfig.atomicWrite(path, json.encodeToString(entries.toList()).toByteArray())
    }

    private fun load() {
        if (!Files.exists(path)) return
        PrivateConfig.harden(path) // апгрейд старого мир-читаемого файла при первом чтении
        runCatching { json.decodeFromString<List<Tunnel>>(Files.readAllBytes(path).decodeToString()) }
            .onSuccess { entries += it }
    }
}
