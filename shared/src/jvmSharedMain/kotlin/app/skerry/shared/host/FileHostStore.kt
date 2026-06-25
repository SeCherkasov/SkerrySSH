package app.skerry.shared.host

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Файловое [HostStore]: профили хранятся одним JSON-массивом [Host]. В отличие от
 * append-only [app.skerry.shared.ssh.FileKnownHostsStore] здесь нужен upsert/remove,
 * поэтому при каждой мутации файл переписывается целиком из in-memory кеша. Запись
 * атомарна (во временный файл рядом + ATOMIC_MOVE), чтобы сбой посреди записи не оставил
 * усечённый JSON. Битый или нечитаемый файл при загрузке трактуется как пустой стор.
 *
 * Методы синхронизированы: вызовы идут из UI-корутины, но контракт прост и держит
 * инвариант кеша согласованным с файлом.
 */
class FileHostStore(private val path: Path) : HostStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entries = mutableListOf<Host>()

    init {
        load()
    }

    @Synchronized
    override fun all(): List<Host> = entries.toList()

    @Synchronized
    override fun put(host: Host) {
        val index = entries.indexOfFirst { it.id == host.id }
        if (index >= 0) entries[index] = host else entries += host
        persist()
    }

    @Synchronized
    override fun remove(id: String) {
        if (entries.removeAll { it.id == id }) persist()
    }

    @Synchronized
    override fun reorder(transform: (List<Host>) -> List<Host>) {
        val updated = transform(entries.toList())
        require(updated.map { it.id }.toSet() == entries.map { it.id }.toSet()) {
            // Без самих Host в сообщении — у них есть credentialId.
            "reorder must preserve the id set (had ${entries.size}, got ${updated.size})"
        }
        entries.clear()
        entries += updated
        persist()
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
        runCatching { json.decodeFromString<List<Host>>(Files.readAllBytes(path).decodeToString()) }
            .onSuccess { entries += it }
    }
}
