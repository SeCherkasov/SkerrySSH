package app.skerry.shared.vault

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Хранилище keychain-секретов [Credential] поверх [Vault]: каждый секрет — запись
 * [RecordType.CREDENTIAL], чей payload — JSON-сериализация [Credential] (label и секрет внутри
 * зашифрованного blob). Чистая common-логика над контрактом [Vault] — платформенной части нет.
 *
 * Требует разблокированного vault: CRUD на залоченном бросает из самого [Vault]. Записи, чей
 * payload не расшифровался или не распарсился (битьё/несовместимая миграция), молча пропускаются —
 * одна повреждённая запись не должна валить список.
 */
class CredentialStore(private val vault: Vault) {

    /** Все живые секреты (tombstone и записи других типов отброшены). */
    fun all(): List<Credential> =
        vault.records()
            .filter { it.type == RecordType.CREDENTIAL && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }

    /** Секрет по [id] или `null`, если его нет, он удалён или payload не читается. */
    fun get(id: String): Credential? {
        val record = vault.records()
            .firstOrNull { it.id == id && it.type == RecordType.CREDENTIAL && !it.deleted }
            ?: return null
        return decode(vault.openPayload(record.id))
    }

    /** Создать/обновить секрет (upsert по [Credential.id]). */
    fun put(credential: Credential) {
        vault.put(credential.id, RecordType.CREDENTIAL, encode(credential))
    }

    /** Мягко удалить секрет (tombstone). Учётки, ссылавшиеся на него, увязываются в слое UI. */
    fun remove(id: String) {
        vault.remove(id)
    }

    private fun encode(credential: Credential): ByteArray =
        json.encodeToString(credential).encodeToByteArray()

    // Один битый/несовместимый payload не должен валить список: SerializationException → null.
    private fun decode(payload: ByteArray?): Credential? =
        payload?.let { runCatching { json.decodeFromString<Credential>(it.decodeToString()) }.getOrNull() }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
