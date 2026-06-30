package app.skerry.shared.host

import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ssh.ConnectionType
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
 * [tags] — необязательные метки для фильтрации списка хостов (чипсы #prod/#docker в макете).
 * Хранятся в канонической форме (без `#`, нижний регистр, без дублей, ≤ [MAX_TAG_LENGTH]) —
 * нормализацию делает [normalizeTag]; [group] (папка) и [tags] (метки) независимы.
 *
 * [identityId] — legacy-указатель прежней двухуровневой модели (хост → учётка → секрет). Новый код
 * его НИКОГДА не пишет: он существует только чтобы [app.skerry.shared.vault.VaultMigration] могла
 * прочитать старые сохранённые файлы хостов (ключ `identityId`) и схлопнуть их в [credentialId],
 * после чего поле зануляется. TODO: удалить через релиз, когда не останется старых файлов.
 *
 * [aiPolicy] — per-host политика AI (принцип «AI under policy»). Дефолт [AiPolicy.Strict] безопасен:
 * для уже сохранённых хостов (поле отсутствует) и новых по умолчанию облако запрещено, пока
 * пользователь осознанно не ослабит политику. Сериализуется по имени (обратно совместимо).
 *
 * [connectionType] — транспорт профиля (см. [ConnectionType]). Дефолт [ConnectionType.SSH] сохраняет
 * обратную совместимость: у старых файлов поле отсутствует и читается как SSH. Для [ConnectionType.TELNET]
 * значимы только [address]/[port] (аутентификации/секрета нет). Для [ConnectionType.SERIAL] [address]
 * несёт имя устройства (напр. `/dev/ttyUSB0`, `COM3`), а [port] — скорость (baud); [username]/[credentialId]
 * не используются.
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
    val tags: List<String> = emptyList(),
    val aiPolicy: AiPolicy = AiPolicy.Strict,
    val connectionType: ConnectionType = ConnectionType.SSH,
)
