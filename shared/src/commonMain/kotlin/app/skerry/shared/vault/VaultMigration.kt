package app.skerry.shared.vault

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Разовая миграция к двухуровневой модели Vault (keychain + учётки). До разделения запись
 * [RecordType.IDENTITY] хранила сырой секрет (`{id, label, auth}`), а [Host.identityId] ссылался
 * прямо на него. После — секреты живут в keychain ([RecordType.CREDENTIAL], [Credential]), а хост
 * ссылается на учётку ([Identity] с `username` + `credentialId`).
 *
 * Миграция самоопределяющаяся и идемпотентная (без отдельного флага): старый формат IDENTITY
 * распознаётся по наличию поля `auth` ([LegacyIdentity]); новые учётки этого поля не имеют и
 * пропускаются, как и уже переведённые в CREDENTIAL записи. Безопасно вызывать при каждом unlock.
 *
 * Требует разблокированного [Vault]. Хосты живут в отдельном [HostStore], поэтому миграция
 * кросс-сторовая: сперва секреты IDENTITY→CREDENTIAL (с сохранением id), затем для каждого хоста с
 * мигрированным `identityId` создаётся учётка-обёртка (`username` берётся из хоста), и `identityId`
 * перенаправляется на неё. Хосты с одинаковыми `(username, секрет)` делят одну учётку.
 */
class VaultMigration(
    private val vault: Vault,
    private val hostStore: HostStore,
    private val newId: () -> String,
) {

    /** Выполнить миграцию; возвращает `true`, если что-то изменилось (для логов/тестов). */
    fun migrate(): Boolean {
        // 1. Старые секреты IDENTITY → CREDENTIAL (тот же id: на него ещё ссылаются хосты).
        val migratedSecretIds = mutableSetOf<String>()
        for (record in vault.records()) {
            if (record.type != RecordType.IDENTITY || record.deleted) continue
            val legacy = decodeLegacy(vault.openPayload(record.id)) ?: continue
            vault.put(record.id, RecordType.CREDENTIAL, encodeCredential(Credential(record.id, legacy.label, legacy.auth)))
            migratedSecretIds += record.id
        }

        // 1b. Завершаем прерванный прошлый прогон: если шаг 1 успел перевести секрет в CREDENTIAL, но
        //     процесс упал до шага 2, хост остался ссылаться прямо на CREDENTIAL (а не на учётку) —
        //     иначе он навсегда останется несвязанным. Такие секреты тоже нуждаются в учётке-обёртке.
        val credentialIds = vault.records()
            .filter { it.type == RecordType.CREDENTIAL && !it.deleted }
            .mapTo(mutableSetOf()) { it.id }
        for (host in hostStore.all()) {
            val sid = host.identityId ?: continue
            if (sid in credentialIds) migratedSecretIds += sid
        }
        if (migratedSecretIds.isEmpty()) return false

        // 2. Для каждого хоста с мигрированным секретом — учётка-обёртка; общие (username, секрет)
        //    делят одну учётку, чтобы не плодить дубликаты. Хосты, уже указывающие на учётку
        //    (IDENTITY, а не CREDENTIAL), сюда не попадают — их id нет в migratedSecretIds.
        val accountByKey = mutableMapOf<Pair<String, String>, String>() // (username, secretId) → accountId
        for (host in hostStore.all()) {
            val secretId = host.identityId ?: continue
            if (secretId !in migratedSecretIds) continue
            val key = host.username to secretId
            val accountId = accountByKey.getOrPut(key) {
                val id = newId()
                // Читаемый label учётки: «user@addr» (первого хоста группы) — username один на группу.
                val label = "${host.username}@${host.address}"
                vault.put(id, RecordType.IDENTITY, encodeIdentity(Identity(id, label, host.username, secretId)))
                id
            }
            hostStore.put(host.copy(identityId = accountId))
        }
        return true
    }

    private fun encodeCredential(c: Credential): ByteArray = json.encodeToString(Credential.serializer(), c).encodeToByteArray()
    private fun encodeIdentity(i: Identity): ByteArray = json.encodeToString(Identity.serializer(), i).encodeToByteArray()

    // Старый формат IDENTITY: распарсился как {id,label,auth} → это секрет, подлежит миграции.
    // Новая учётка (поля username/credentialId, без auth) сюда не пройдёт → пропуск (идемпотентность).
    private fun decodeLegacy(payload: ByteArray?): LegacyIdentity? =
        payload?.let { runCatching { json.decodeFromString<LegacyIdentity>(it.decodeToString()) }.getOrNull() }

    /** Прежняя форма записи IDENTITY (сырой секрет). [auth] делит wire-имена с [CredentialSecret]. */
    @Serializable
    private data class LegacyIdentity(
        val id: String,
        val label: String,
        @SerialName("auth") val auth: CredentialSecret,
    )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
