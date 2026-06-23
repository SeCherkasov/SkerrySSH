package app.skerry.shared.vault

import app.skerry.shared.host.HostStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Разовая миграция схлопывания модели Vault с двух уровней (хост → учётка → секрет) до одного
 * (хост → секрет). До схлопывания запись [RecordType.IDENTITY] хранила либо сырой секрет
 * (`{id, label, auth}`), либо учётку-обёртку (`{id, label, username, credentialId}`), а
 * [app.skerry.shared.host.Host.identityId] ссылался на одно из них. После — секреты живут в keychain
 * ([RecordType.CREDENTIAL], [Credential]), а хост ссылается на них напрямую через `credentialId`.
 *
 * Миграция самоопределяющаяся и идемпотентная (без отдельного флага). Безопасно вызывать при каждом
 * unlock. Требует разблокированного [Vault]; хосты живут в отдельном [HostStore], поэтому миграция
 * кросс-сторовая: секреты IDENTITY→CREDENTIAL (с сохранением id), учётки-обёртки схлопываются в
 * ссылку хоста на сам секрет, затем записи-обёртки удаляются.
 */
class VaultMigration(
    private val vault: Vault,
    private val hostStore: HostStore,
) {

    /** Выполнить миграцию; возвращает `true`, если что-то изменилось (для логов/тестов). */
    fun migrate(): Boolean {
        var changed = false

        // a) Старые сырые секреты IDENTITY → CREDENTIAL (тот же id: на него ещё ссылаются хосты).
        //    Учётки-обёртки (поля username/credentialId, без auth) сюда не пройдут → пропуск.
        for (record in vault.records()) {
            if (record.type != RecordType.IDENTITY || record.deleted) continue
            val legacy = decodeSecret(vault.openPayload(record.id)) ?: continue
            vault.put(record.id, RecordType.CREDENTIAL, encodeCredential(Credential(record.id, legacy.label, legacy.auth)))
            changed = true
        }

        // Живые keychain-секреты после шага (a) — только на такой id допустимо перепривязать хост.
        val liveCredentials = vault.records()
            .filter { it.type == RecordType.CREDENTIAL && !it.deleted }
            .mapTo(mutableSetOf()) { it.id }

        // b) Учётки-обёртки → их credentialId, НО только если target-секрет реально жив. Обёртку с
        //    пропавшим секретом (повреждение/частичный прогон) не схлопываем: не плодим висячую
        //    ссылку и не сносим единственного свидетеля связи.
        val wrapper = mutableMapOf<String, String>()
        for (record in vault.records()) {
            if (record.type != RecordType.IDENTITY || record.deleted) continue
            val account = decodeAccount(vault.openPayload(record.id)) ?: continue
            if (account.credentialId in liveCredentials) wrapper[record.id] = account.credentialId
        }

        // c) Перепривязываем хосты на ЖИВОЙ секрет: legacy → обёртка.credentialId, либо сам legacy,
        //    если он уже id живого секрета (прерванный прогон/прямая ссылка). Не резолвится в живой
        //    секрет → развязываем (credentialId=null, спросит пароль) — fail-safe вместо висячей ссылки.
        for (host in hostStore.all()) {
            val legacy = host.identityId ?: continue
            val credId = (wrapper[legacy] ?: legacy).takeIf { it in liveCredentials }
            hostStore.put(host.copy(credentialId = credId, identityId = null))
            changed = true
        }

        // d) Сносим только схлопнутые обёртки (их target жив, хосты перепривязаны).
        for (id in wrapper.keys) {
            vault.remove(id)
            changed = true
        }

        return changed
    }

    private fun encodeCredential(c: Credential): ByteArray =
        json.encodeToString(Credential.serializer(), c).encodeToByteArray()

    // Старый формат секрета: {id,label,auth} → подлежит миграции в CREDENTIAL. Обёртка (без auth)
    // сюда не пройдёт благодаря обязательному полю auth. Доп. дискриминатор: запись с credentialId —
    // это учётка, а не секрет (даже если у неё нашёлся бы auth), её НЕ конвертируем в CREDENTIAL.
    private fun decodeSecret(payload: ByteArray?): LegacySecret? =
        payload?.let { runCatching { json.decodeFromString(LegacySecret.serializer(), it.decodeToString()) }.getOrNull() }
            ?.takeIf { it.credentialId == null }

    // Учётка-обёртка: {id,label,username,credentialId}. Сырой секрет (без credentialId) сюда не пройдёт.
    private fun decodeAccount(payload: ByteArray?): LegacyAccount? =
        payload?.let { runCatching { json.decodeFromString(LegacyAccount.serializer(), it.decodeToString()) }.getOrNull() }

    /**
     * Прежняя форма записи IDENTITY (сырой секрет). [auth] делит wire-имена с [CredentialSecret].
     * [credentialId] — дискриминатор: у настоящего секрета его нет; присутствие означает учётку-
     * обёртку (её разбирает [LegacyAccount]), такую запись [decodeSecret] отвергает.
     */
    @Serializable
    private data class LegacySecret(
        val id: String,
        val label: String,
        @SerialName("auth") val auth: CredentialSecret,
        val credentialId: String? = null,
    )

    /** Прежняя форма записи IDENTITY (учётка-обёртка): username + ссылка на keychain-секрет. */
    @Serializable
    private data class LegacyAccount(
        val id: String,
        val label: String,
        val username: String,
        val credentialId: String,
    )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
