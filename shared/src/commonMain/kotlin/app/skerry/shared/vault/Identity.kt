package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/**
 * Учётка подключения (как «identity» в популярных SSH-клиентах): пара «имя пользователя + способ входа»,
 * переиспользуемая на нескольких хостах. Хост ссылается на учётку по [id]
 * ([app.skerry.shared.host.Host.identityId]), а учётка — на keychain-секрет по [credentialId]
 * ([Credential.id]). Двухуровневая ссылка (хост → учётка → credential) даёт смену ключа/пароля
 * в одном месте для всех зависимых хостов.
 *
 * [username] — имя пользователя для входа (раньше дублировалось в каждом хосте). [credentialId]
 * указывает на ровно один [Credential] в том же vault; учётка не хранит секрет inline — только
 * ссылку, поэтому секрет живёт единственным экземпляром в keychain.
 *
 * Целиком лежит в зашифрованном payload записи [RecordType.IDENTITY]: открытые метаданные не
 * раскрывают [label]/[username]. `toString` редактит [label]/[username], оставляя [id]/[credentialId]
 * (структурные ссылки, не секрет) — помогает диагностике без утечки имён.
 */
@Serializable
data class Identity(
    val id: String,
    val label: String,
    val username: String,
    val credentialId: String,
) {
    override fun toString(): String =
        "Identity(id=$id, label=redacted, username=redacted, credentialId=$credentialId)"
}
