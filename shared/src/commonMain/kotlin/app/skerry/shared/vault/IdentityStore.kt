package app.skerry.shared.vault

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Хранилище переиспользуемых [Identity] поверх [Vault]: каждая identity — запись
 * [RecordType.IDENTITY], чей payload — JSON-сериализация [Identity] (label и секрет внутри
 * зашифрованного blob). Чистая common-логика над контрактом [Vault] — платформенной части нет.
 *
 * Требует разблокированного vault: CRUD на залоченном бросает из самого [Vault]. Записи, чей
 * payload не расшифровался или не распарсился (битьё/несовместимая миграция), молча пропускаются —
 * одна повреждённая запись не должна валить список.
 */
class IdentityStore(private val vault: Vault) {

    /** Все живые identity (tombstone и записи других типов отброшены). */
    fun all(): List<Identity> =
        vault.records()
            .filter { it.type == RecordType.IDENTITY && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }

    /** Identity по [id] или `null`, если её нет, она удалена или payload не читается. */
    fun get(id: String): Identity? {
        val record = vault.records()
            .firstOrNull { it.id == id && it.type == RecordType.IDENTITY && !it.deleted }
            ?: return null
        return decode(vault.openPayload(record.id))
    }

    /** Создать/обновить identity (upsert по [Identity.id]). */
    fun put(identity: Identity) {
        vault.put(identity.id, RecordType.IDENTITY, encode(identity))
    }

    /** Мягко удалить identity (tombstone). Хосты, ссылавшиеся на неё, увязываются в слое UI. */
    fun remove(id: String) {
        vault.remove(id)
    }

    private fun encode(identity: Identity): ByteArray =
        json.encodeToString(identity).encodeToByteArray()

    // Один битый/несовместимый payload не должен валить список: SerializationException → null.
    private fun decode(payload: ByteArray?): Identity? =
        payload?.let { runCatching { json.decodeFromString<Identity>(it.decodeToString()) }.getOrNull() }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
