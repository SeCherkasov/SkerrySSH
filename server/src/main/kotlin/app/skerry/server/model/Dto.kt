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

/** Дельта: записи + новый курсор синхронизации, который клиент сохраняет как `lastSyncVersion`. */
@Serializable
data class RecordsResponse(val records: List<RecordDto>, val cursor: Long)

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
data class AdminDevicesResponse(val devices: List<AdminDeviceDto>)

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
data class AdminActivityResponse(val events: List<AdminActivityDto>)

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
