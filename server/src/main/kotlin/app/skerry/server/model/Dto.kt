package app.skerry.server.model

import kotlinx.serialization.Serializable

/**
 * Контракт REST/WS sync-сервера (`docs/skerry-sync-design.md` §3). Шифроблобы передаются
 * как base64-строки (`blob`, `wrappedDataKey`, `encryptedDataKey`) — сервер их не расшифровывает.
 * Клиентская сторона (`shared/sync`) держит зеркальные модели; единый JSON-контракт проверяется
 * e2e-тестом.
 */

// --- auth ---

@Serializable
data class RegisterRequest(
    val accountId: String,
    val srpSalt: String,
    val srpVerifier: String,
    val wrappedDataKey: String,
    val deviceId: String,
    val deviceName: String,
    // Опционально (default null): старые клиенты без поля остаются совместимыми по wire.
    val platform: String? = null,
)

@Serializable
data class ChallengeRequest(val accountId: String)

@Serializable
data class ChallengeResponse(val challengeId: String, val salt: String, val b: String)

@Serializable
data class VerifyRequest(
    val challengeId: String,
    val a: String,
    val m1: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String? = null,
)

@Serializable
data class VerifyResponse(val m2: String, val accessToken: String, val refreshToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String)

// --- vault ---

@Serializable
data class KeysResponse(val wrappedDataKey: String)

@Serializable
data class RecordDto(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: String,
)

/**
 * Дельта: записи + новый курсор синхронизации, который клиент сохраняет как `lastSyncVersion`.
 * [compactedIds] — id надгробий, полностью распространённых на все устройства (serverSeq ≤ watermark):
 * клиент по ним физически забывает тромбстоуны и перестаёт их пушить (иначе re-push воскрешал бы их
 * после purge). Поле с дефолтом — старый клиент его игнорирует.
 */
@Serializable
data class RecordsResponse(
    val records: List<RecordDto>,
    val cursor: Long,
    val compactedIds: List<String> = emptyList(),
)

@Serializable
data class PushRequest(val records: List<RecordDto>)

/** Победившее по LWW состояние каждой посланной записи + новый курсор. */
@Serializable
data class PushResponse(val records: List<RecordDto>, val cursor: Long)

// --- devices ---

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
    val current: Boolean,
)

@Serializable
data class DevicesResponse(val devices: List<DeviceDto>)

/**
 * Устройство в админ-консоли: те же открытые метаданные, что и в [DeviceDto], плюс `accountId`
 * (консоль видит все аккаунты инстанса и отзывает по паре accountId+id — deviceId уникален лишь
 * в пределах аккаунта). Содержимого по-прежнему нет.
 */
@Serializable
data class AdminDeviceDto(
    val accountId: String,
    val id: String,
    val name: String,
    val platform: String?,
    val createdAt: Long,
    val lastSeenAt: Long,
    val syncVersion: Long?,
    val revoked: Boolean,
)

@Serializable
data class AdminDevicesResponse(val devices: List<AdminDeviceDto>, val total: Long)

/** Событие аудит-лога для консоли: только метаданные синхронизации, `createdAt` — epoch millis. */
@Serializable
data class AdminActivityDto(
    val accountId: String,
    val deviceId: String?,
    val event: String,
    val detail: String,
    val createdAt: Long,
)

@Serializable
data class AdminActivityResponse(val events: List<AdminActivityDto>, val total: Long)

/**
 * Аккаунт инстанса для консоли: открытые метаданные ([id] — он же email/identity) и агрегаты,
 * посчитанные на стороне БД. Содержимого записей здесь нет — только их число, число tombstone'ов
 * и суммарный размер шифроблобов. [lastSeenAt] — самая свежая активность любого устройства аккаунта.
 */
@Serializable
data class AdminAccountDto(
    val id: String,
    val createdAt: Long,
    val syncSeq: Long,
    val devices: Int,
    val activeDevices: Int,
    val records: Int,
    val tombstones: Int,
    val storageBytes: Long,
    val lastSeenAt: Long?,
)

@Serializable
data class AdminAccountsResponse(val accounts: List<AdminAccountDto>, val total: Long)

/**
 * Envelope записи vault, как её РЕАЛЬНО видит сервер: открытые метаданные синхронизации плюс размер
 * шифроблоба и [previewHex] — первые байты настоящего шифротекста (непрозрачный шум). Содержимого
 * нет по определению: без dataKey блоб нечитаем. Это честная замена прежней нарисованной карточки.
 */
@Serializable
data class AdminRecordDto(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blobBytes: Int,
    val serverSeq: Long,
    val previewHex: String,
)

@Serializable
data class AdminRecordsResponse(val accountId: String, val records: List<AdminRecordDto>)

/** Результат purge tombstone'ов: сколько надгробий физически удалено (освобождено места). */
@Serializable
data class AdminPurgeResponse(val purged: Int)

// --- pairing (вариант B) ---

@Serializable
data class PairingStartRequest(val encryptedDataKey: String, val ttlSeconds: Long? = null)

@Serializable
data class PairingStartResponse(val code: String, val expiresAt: Long)

@Serializable
data class PairingClaimRequest(val code: String, val deviceId: String, val deviceName: String)

@Serializable
data class PairingClaimResponse(
    val accountId: String,
    val encryptedDataKey: String,
    val accessToken: String,
    val refreshToken: String,
)

// --- admin / errors ---

@Serializable
data class StatsResponse(
    val accounts: Long,
    val devices: Long,
    val records: Long,
    val pairingSessions: Long,
    val storageBytes: Long,
)

@Serializable
data class HealthResponse(val status: String, val version: String)

@Serializable
data class ErrorResponse(val error: String)
