package app.skerry.server.db

/** Аккаунт в том виде, как его хранит сервер (всё, кроме [wrappedDataKey], — открыто). */
data class AccountRow(
    val id: String,
    val srpSalt: String,
    val srpVerifier: String,
    val wrappedDataKey: ByteArray,
    val syncSeq: Long,
)

data class DeviceRow(
    val id: String,
    val accountId: String,
    val name: String,
    val platform: String?,
    val createdAt: Long,
    val lastSeenAt: Long,
    val lastSyncVersion: Long?,
    val revoked: Boolean,
)

/**
 * Зашифрованная запись vault на сервере. Зеркалит клиентский `VaultRecord` плюс [serverSeq] —
 * присвоенный сервером монотонный курсор для дельта-синхронизации.
 */
data class StoredRecord(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
    val serverSeq: Long,
)

data class PairingRow(
    val code: String,
    val accountId: String,
    val encryptedDataKey: ByteArray,
    val expiresAt: Long,
    val consumed: Boolean,
)

/** Строка аудит-лога (только метаданные синхронизации, без содержимого записей). */
data class ActivityRow(
    val seq: Long,
    val accountId: String,
    val deviceId: String?,
    val event: String,
    val detail: String,
    val createdAt: Long,
)
