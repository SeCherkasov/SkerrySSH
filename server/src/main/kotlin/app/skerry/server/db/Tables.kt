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
    val createdAt = long("created_at")
    val lastSeenAt = long("last_seen_at")
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
