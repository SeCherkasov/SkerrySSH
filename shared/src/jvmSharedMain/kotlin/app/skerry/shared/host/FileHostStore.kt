package app.skerry.shared.host

import app.skerry.shared.io.PrivateConfig
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Файловое [HostStore]: профили хранятся одним JSON-массивом [Host]. В отличие от
 * append-only [app.skerry.shared.ssh.FileKnownHostsStore] здесь нужен upsert/remove,
 * поэтому при каждой мутации файл переписывается целиком из in-memory кеша. Запись
 * приватна и атомарна ([PrivateConfig.atomicWrite], 0600), чтобы профили не были мир-читаемыми,
 * а сбой посреди записи не оставил усечённый JSON. Битый/нечитаемый файл при загрузке — пустой стор.
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
        // Размер + множество id: одно равенство множеств пропустило бы дубликат ([A,B,C,A]), который
        // затем осиротил бы одну из копий (find/put адресуют первую по indexOfFirst). Симметрично
        // VaultHostStore.reorder. Без самих Host в сообщении — у них есть credentialId.
        require(updated.size == entries.size && updated.map { it.id }.toSet() == entries.map { it.id }.toSet()) {
            "reorder must preserve the id set (had ${entries.size}, got ${updated.size})"
        }
        entries.clear()
        entries += updated
        persist()
    }

    private fun persist() {
        // Files.writeString/readString требуют Android API 33+; пишем байтами (UTF-8) ради minSdk 26.
        PrivateConfig.atomicWrite(path, json.encodeToString(entries.toList()).toByteArray())
    }

    private fun load() {
        if (!Files.exists(path)) return
        PrivateConfig.harden(path) // апгрейд старого мир-читаемого файла при первом чтении
        runCatching { json.decodeFromString<List<Host>>(Files.readAllBytes(path).decodeToString()) }
            .onSuccess { entries += it }
    }
}
