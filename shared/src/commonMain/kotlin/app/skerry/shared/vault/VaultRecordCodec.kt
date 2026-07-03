package app.skerry.shared.vault

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Общий низ vault-backed сторов: записи типа [type], чей payload — JSON-сериализация [T]
 * (единый `Json { ignoreUnknownKeys = true }`, байты на диске идентичны прежним пер-сторовым
 * кодекам). Кодек НЕ решает политику залоченного vault — гейт `isUnlocked` остаётся на сторе
 * (у одних чтение на залоченном — пустой список/дефолт, у других — бросок из самого [Vault]).
 *
 * Битый/нерасшифровавшийся payload декодируется в `null` и молча пропускается — одна
 * повреждённая запись не валит список (общее правило всех сторов).
 */
internal class VaultRecordCodec<T>(
    private val vault: Vault,
    private val type: RecordType,
    private val serializer: KSerializer<T>,
) {

    /** Все живые записи типа (tombstone и чужие типы отброшены); битый payload пропущен. */
    fun list(): List<T> =
        vault.records()
            .filter { it.type == type && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }

    /** Значение по [id] или `null`, если записи нет, она удалена или payload не читается. */
    fun get(id: String): T? {
        val record = vault.records()
            .firstOrNull { it.id == id && it.type == type && !it.deleted }
            ?: return null
        return decode(vault.openPayload(record.id))
    }

    /** Создать/обновить запись (upsert по [id]). */
    fun put(id: String, value: T) {
        vault.put(id, type, encode(value))
    }

    /** Мягко удалить запись (tombstone) — делегат [Vault.remove]. */
    fun remove(id: String) {
        vault.remove(id)
    }

    fun encode(value: T): ByteArray = json.encodeToString(serializer, value).encodeToByteArray()

    fun decode(payload: ByteArray?): T? =
        payload?.let { runCatching { json.decodeFromString(serializer, it.decodeToString()) }.getOrNull() }

    internal companion object {
        // Единый Json всех vault-сторов: незнакомые поля игнорируются (записи новых версий читаемы).
        internal val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Singleton-запись в vault (настройки/макет): фиксированный [id] + [type], значение — JSON [T].
 * [load] на залоченном vault, при отсутствии записи, битом payload или броске из
 * [Vault.openPayload] отдаёт [default] — вспомогательное значение не должно ронять вызывающего
 * (например, цикл sync). [save] требует разблокированного vault ([Vault.put]).
 */
internal class VaultSingletonStore<T>(
    private val vault: Vault,
    private val id: String,
    private val type: RecordType,
    serializer: KSerializer<T>,
    private val default: () -> T,
) {

    private val codec = VaultRecordCodec(vault, type, serializer)

    fun load(): T {
        if (!vault.isUnlocked) return default()
        val record = vault.records().firstOrNull { it.id == id && it.type == type && !it.deleted }
            ?: return default()
        // openPayload оборачиваем: даже если реализация бросит на I/O/AEAD (а не вернёт null),
        // вызывающий должен получить дефолт, а не падение (drainPull синка это бы прервало).
        return codec.decode(runCatching { vault.openPayload(record.id) }.getOrNull()) ?: default()
    }

    fun save(value: T) {
        codec.put(id, value)
    }
}
