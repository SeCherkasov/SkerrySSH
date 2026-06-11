package app.skerry.shared.host

import kotlinx.serialization.Serializable

/**
 * Сохранённый профиль подключения в менеджере хостов. Идентичность — стабильный [id]
 * (назначается при создании, не меняется при правках), поэтому переименование [label]
 * или смена адреса не теряет историю/привязки. [label] — отображаемое имя, [address] —
 * хост или IP для набора, [group] — необязательная папка для группировки в списке.
 *
 * Сам секрет здесь НЕ хранится: он лежит в зашифрованном vault как
 * [app.skerry.shared.vault.Identity], а профиль ссылается на него по [identityId]
 * (переиспользуемый секрет — один ключ/пароль на несколько хостов). `null` — секрет не
 * привязан, пароль вводится при подключении (прежнее поведение).
 */
@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
    val identityId: String? = null,
)
