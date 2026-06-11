package app.skerry.shared.host

import kotlinx.serialization.Serializable

/**
 * Сохранённый профиль подключения в менеджере хостов. Идентичность — стабильный [id]
 * (назначается при создании, не меняется при правках), поэтому переименование [label]
 * или смена адреса не теряет историю/привязки. [label] — отображаемое имя, [address] —
 * хост или IP для набора, [group] — необязательная папка для группировки в списке.
 *
 * Секрет аутентификации здесь НЕ хранится: пароль вводится при подключении. Хранение
 * паролей/ключей придёт в зашифрованный vault вместе с мастер-паролем (см.
 * `docs/skerry-sync-design.md`), как и у [app.skerry.shared.ssh.KnownHost] сейчас.
 */
@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
)
