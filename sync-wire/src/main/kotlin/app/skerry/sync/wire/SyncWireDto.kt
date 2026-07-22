package app.skerry.sync.wire

import kotlinx.serialization.Serializable

/**
 * Wire JSON contract between client and sync server; the single
 * source for both sides (`server` and `shared/sync`). Ciphertext travels as base64 strings (`blob`,
 * `wrappedDataKey`, `encryptedDataKey`); the server never decrypts them. Server admin DTOs (console
 * sees metadata only) stay in `server/.../model/Dto.kt`, unknown to the client.
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
    // Optional (default null): older clients without this field stay wire-compatible.
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

/**
 * Rotate the account password (issue #32). [challengeId]/[a]/[m1] are a fresh SRP proof of the
 * CURRENT password (obtained via the same `/auth/srp/challenge`) — the server verifies them before
 * touching anything, so a stolen access token alone can't rotate. [newSrpSalt]/[newSrpVerifier] are
 * derived from the NEW password, [newWrappedDataKey] is the account dataKey re-wrapped under the new
 * master key (base64) — the dataKey itself is unchanged. The server swaps the verifier and the wrap
 * in one transaction and revokes every device except [deviceId], forcing them to re-authenticate
 * with the new password.
 */
@Serializable
data class ChangePasswordRequest(
    val challengeId: String,
    val a: String,
    val m1: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String? = null,
    val newSrpSalt: String,
    val newSrpVerifier: String,
    val newWrappedDataKey: String,
)

/** [m2] is the server's SRP counter-proof (client verifies it, as in login); tokens are fresh for the acting device. */
@Serializable
data class ChangePasswordResponse(val m2: String, val accessToken: String, val refreshToken: String)

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
 * Delta: records plus the new sync cursor, which the client stores as `lastSyncVersion`.
 * [compactedIds] are tombstone ids fully propagated to all devices (serverSeq <= watermark); the
 * client physically forgets these tombstones and stops pushing them, otherwise a re-push would
 * resurrect them after purge. Field has a default so old clients ignore it.
 */
@Serializable
data class RecordsResponse(
    val records: List<RecordDto>,
    val cursor: Long,
    val compactedIds: List<String> = emptyList(),
)

@Serializable
data class PushRequest(val records: List<RecordDto>)

/** LWW-winning state of each pushed record, plus the new cursor. */
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

// --- pairing (variant B) ---

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
