package app.skerry.shared.host

import kotlinx.serialization.Serializable

/**
 * Сохранённый профиль подключения в менеджере хостов. Идентичность — стабильный [id]
 * (назначается при создании, не меняется при правках), поэтому переименование [label]
 * или смена адреса не теряет историю/привязки. [label] — отображаемое имя, [address] —
 * хост или IP для набора, [group] — необязательная папка для группировки в списке.
 *
 * Сам секрет здесь НЕ хранится: он лежит в зашифрованном vault как
 * [app.skerry.shared.vault.Credential] (keychain), а профиль ссылается на него по [credentialId]
 * (переиспользуемый секрет — один ключ/пароль на несколько хостов). `null` — секрет не
 * привязан, пароль вводится при подключении (прежнее поведение).
 *
 * [identityId] — legacy-указатель прежней двухуровневой модели (хост → учётка → секрет). Новый код
 * его НИКОГДА не пишет: он существует только чтобы [app.skerry.shared.vault.VaultMigration] могла
 * прочитать старые сохранённые файлы хостов (ключ `identityId`) и схлопнуть их в [credentialId],
 * после чего поле зануляется. TODO: удалить через релиз, когда не останется старых файлов.
 */
@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
    val credentialId: String? = null,
    val identityId: String? = null,
)
