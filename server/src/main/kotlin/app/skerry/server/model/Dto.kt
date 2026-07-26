package app.skerry.server.model

import kotlinx.serialization.Serializable

/**
 * Server-only DTOs: admin console, stats, errors. The client<->server wire contract
 * (auth/vault/devices/pairing) lives in the `:sync-wire`
 * module (`app.skerry.sync.wire`), the single source shared by both sides.
 */

/**
 * Device in the admin console: the same plaintext metadata as [DeviceDto], plus `accountId`
 * (the console sees all accounts on the instance and revokes by accountId+id, since deviceId is
 * only unique within an account). No content here either.
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

/** Audit log event for the console: sync metadata only, `createdAt` is epoch millis. */
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
 * Instance account for the console: plaintext metadata ([id] doubles as email/identity) and
 * aggregates computed in the DB. No record content, only counts of records and tombstones and
 * total ciphertext size. [lastSeenAt] is the most recent activity across the account's devices.
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
 * A vault record envelope as the server actually sees it: plaintext sync metadata plus ciphertext
 * size and [previewHex] (leading bytes of the real ciphertext, opaque noise). No content by
 * construction: the blob is unreadable without dataKey.
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

/** Result of a tombstone purge: how many tombstones were physically deleted. */
@Serializable
data class AdminPurgeResponse(val purged: Int)

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

/**
 * `GET /admin/observability`: is monitoring actually wired up on this instance? The console has no
 * other way to tell — /metrics may be disabled, and a stale inventory looks fine from the outside.
 * [inventoryAgeSeconds] is null when nothing has been collected yet: 0 would read as "just now".
 */
@Serializable
data class AdminObservabilityDto(
    val metrics: String,
    val ready: Boolean,
    val inventoryIntervalSeconds: Long,
    val inventoryAgeSeconds: Long?,
)

/**
 * `GET /readyz`: whether this instance can serve sync requests right now. Separate from
 * [HealthResponse] because liveness must not depend on the database — see the route comment.
 */
@Serializable
data class ReadyResponse(val status: String, val db: String)

@Serializable
data class ErrorResponse(val error: String)
