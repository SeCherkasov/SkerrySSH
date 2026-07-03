package app.skerry.shared.vault

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

    private val codec = VaultRecordCodec(vault, RecordType.CREDENTIAL, Credential.serializer())

    /** Все живые секреты (tombstone и записи других типов отброшены). */
    fun all(): List<Credential> = codec.list()

    /** Секрет по [id] или `null`, если его нет, он удалён или payload не читается. */
    fun get(id: String): Credential? = codec.get(id)

    /** Создать/обновить секрет (upsert по [Credential.id]). */
    fun put(credential: Credential) {
        codec.put(credential.id, credential)
    }

    /** Мягко удалить секрет (tombstone). Учётки, ссылавшиеся на него, увязываются в слое UI. */
    fun remove(id: String) {
        codec.remove(id)
    }
}
