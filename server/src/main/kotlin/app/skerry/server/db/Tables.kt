package app.skerry.server.db

import org.jetbrains.exposed.sql.Table

/**
 * Схема хранилища sync-сервера. Сервер zero-knowledge: всё, что относится к содержимому
 * пользователя ([Records.blob], [Pairing.encryptedDataKey], [Accounts.wrappedDataKey]) —
 * шифротекст, ключ к которому сервер не видит. Открыто хранятся только метаданные
 * синхронизации (`docs/skerry-sync-design.md` §2).
 *
 * Типы выбраны портируемо между SQLite и PostgreSQL: текстовые идентификаторы, `long` для
 * счётчиков, `blob` для шифроблоков (BLOB в SQLite, bytea в PostgreSQL).
 */
object Accounts : Table("accounts") {
    /** accountId (он же соль Argon2id на клиенте и identity SRP). */
    val id = varchar("id", 320)
    /** Соль SRP `s` (hex) — отдельная от Argon2id-соли. */
    val srpSalt = text("srp_salt")
    /** SRP-верификатор `v` (hex). По нему сервер проверяет вход, не зная пароля. */
    val srpVerifier = text("srp_verifier")
    /** Обёртка dataKey под masterKey — сервер хранит только шифротекст. */
    val wrappedDataKey = blob("wrapped_data_key")
    /** Монотонный per-account курсор синхронизации (watermark для дельты). */
    val syncSeq = long("sync_seq").default(0)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Devices : Table("devices") {
    val id = varchar("id", 64)
    val accountId = varchar("account_id", 320).references(Accounts.id)
    val name = text("name")
    /** Платформа устройства (напр. «Android 34», «Linux»). Открытая метка, как и name. */
    val platform = varchar("platform", 64).nullable()
    val createdAt = long("created_at")
    val lastSeenAt = long("last_seen_at")
    /** Курсор синхронизации, до которого устройство дочиталось/дописалось (открытый счётчик). */
    val lastSyncVersion = long("last_sync_version").nullable()
    val revoked = bool("revoked").default(false)

    // PK по (accountId, id): deviceId уникален в рамках аккаунта, а не глобально — иначе
    // клиент, подставив чужой deviceId, мог бы перехватить/сделать неотзываемой чужую запись
    // устройства (security-ревью H2).
    override val primaryKey = PrimaryKey(accountId, id)
}

/**
 * Зашифрованные записи vault. LWW по ([version], затем `deviceId`); [serverSeq] — отдельная
 * ось: монотонный per-account курсор, по которому клиент делает дельта-выборку (`since`).
 */
object Records : Table("records") {
    val accountId = varchar("account_id", 320).references(Accounts.id)
    val recordId = varchar("record_id", 64)
    val type = varchar("type", 32)
    val version = long("version")
    val updatedAt = text("updated_at")
    val deviceId = varchar("device_id", 64)
    val deleted = bool("deleted")
    val blob = blob("blob")
    /** Присваивается сервером при каждой принятой записи; растёт монотонно в рамках аккаунта. */
    val serverSeq = long("server_seq")

    override val primaryKey = PrimaryKey(accountId, recordId)

    init {
        index("idx_records_delta", false, accountId, serverSeq)
    }
}

/**
 * Аудит-лог метаданных для админ-консоли (`docs/skerry-sync-prototype.html` → Recent activity).
 * Append-only, zero-knowledge: пишем только событие, устройство и человекочитаемую сводку
 * ([detail] — счётчики/курсоры, никогда содержимое записей). Без FK на [Accounts]: лог
 * переживает удаление аккаунта и допускает события до его создания. Удержание — [ActivityRepository].
 */
object ActivityLog : Table("activity_log") {
    val seq = long("seq").autoIncrement()
    val accountId = varchar("account_id", 320)
    val deviceId = varchar("device_id", 64).nullable()
    val event = varchar("event", 32)
    val detail = text("detail")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(seq)
}

/**
 * Публичные X25519-ключи аккаунтов для Teams-приглашений. Публичный ключ — не секрет;
 * подмена ключа сервером обнаруживается сверкой фингерпринта участниками (см.
 * `docs/skerry-sync-design.md`, раздел Teams).
 */
object AccountKeys : Table("account_keys") {
    val accountId = varchar("account_id", 320).references(Accounts.id)
    val publicKey = blob("public_key")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(accountId)
}

/**
 * Команды (шеринг записей между аккаунтами). Zero-knowledge: сервер знает только состав и
 * роли; имя команды и содержимое записей зашифрованы teamKey, которого у сервера нет.
 * [teamSeq] — монотонный per-team курсор дельты (аналог [Accounts.syncSeq]).
 */
object Teams : Table("teams") {
    val id = varchar("id", 64)
    val ownerAccountId = varchar("owner_account_id", 320).references(Accounts.id)
    val teamSeq = long("team_seq").default(0)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Участники команд. [envelope] — sealed-конверт (crypto_box_seal) с teamKey и именем команды,
 * запечатанный на публичный ключ приглашённого: сервер доставляет, но не читает.
 * Статусы: `invited` → `active`; удаление участника = удаление строки (ACL-отзыв).
 */
object TeamMembers : Table("team_members") {
    val teamId = varchar("team_id", 64).references(Teams.id)
    val accountId = varchar("account_id", 320).references(Accounts.id)
    /** `owner` | `member`. */
    val role = varchar("role", 16)
    /** `invited` | `active`. */
    val status = varchar("status", 16)
    val envelope = blob("envelope").nullable()
    val invitedBy = varchar("invited_by", 320)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(teamId, accountId)
}

/**
 * Зашифрованные записи команд — модель [Records], но в team-scope: LWW по (version, deviceId),
 * [teamSeq] — курсор дельты. Тромбстоуны не компактятся watermark'ом (участники приходят и
 * уходят — watermark нестабилен); их чистит периодическая уборка по возрасту.
 */
object TeamRecords : Table("team_records") {
    val teamId = varchar("team_id", 64).references(Teams.id)
    val recordId = varchar("record_id", 64)
    val type = varchar("type", 32)
    val version = long("version")
    val updatedAt = text("updated_at")
    val deviceId = varchar("device_id", 64)
    val deleted = bool("deleted")
    val blob = blob("blob")
    val teamSeq = long("team_seq")

    override val primaryKey = PrimaryKey(teamId, recordId)

    init {
        index("idx_team_records_delta", false, teamId, teamSeq)
    }
}

/** Одноразовые pairing-сессии (вариант B): dataKey, зашифрованный transferKey, с TTL. */
object Pairing : Table("pairing") {
    val code = varchar("code", 64)
    val accountId = varchar("account_id", 320).references(Accounts.id)
    /** dataKey, зашифрованный одноразовым transferKey — сервер видит только шифротекст. */
    val encryptedDataKey = blob("encrypted_data_key")
    val expiresAt = long("expires_at")
    val consumed = bool("consumed").default(false)

    override val primaryKey = PrimaryKey(code)
}
