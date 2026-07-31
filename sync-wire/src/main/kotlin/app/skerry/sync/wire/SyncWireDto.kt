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

/**
 * SRP login response. [reactivated] is `true` only when this device was revoked and this correct-password
 * login cleared the revocation (server-computed, see the device re-enroll audit event). The client uses it
 * to rebuild its vault from the server snapshot before its first push, so a record purged while the device
 * was revoked isn't resurrected by a stale local copy. Default `false` keeps old servers/clients wire-compatible.
 */
@Serializable
data class VerifyResponse(
    val m2: String,
    val accessToken: String,
    val refreshToken: String,
    val reactivated: Boolean = false,
)

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

/**
 * Set, rotate or clear the **web** password — the credential that opens the browser account zone at
 * `/account`, sent by the app over its authenticated session. A null [password] clears it, which
 * also revokes the browser session it was holding open.
 *
 * It is not the master password and derives no key: a browser signed in with it reads the metadata
 * the server already holds in plaintext, and cannot decrypt a record. Whoever serves the page also
 * runs the server the password protects, which is why the master password never travels this way.
 */
@Serializable
data class WebPasswordRequest(val password: String? = null)

/**
 * Whether the account currently has a web password (`GET /auth/web-password`) — the app's Web access
 * screen needs to know before it offers to set one or to take it away. Carries no default on
 * purpose: kotlinx omits a property equal to its default, and the absent field would read back as
 * `false` on a client that parses it, which is the exact state the screen must not invent.
 */
@Serializable
data class WebAccessResponse(val enabled: Boolean)

/**
 * Sign a browser in to the account zone. No deviceId: the server names the web session itself, so
 * this unauthenticated endpoint can't be used to adopt the id of another device. The response is the
 * ordinary [TokenResponse] every client gets.
 */
@Serializable
data class WebLoginRequest(val accountId: String, val password: String)

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

/**
 * [platform] is what the device called itself when it enrolled (`Linux`, `Android`, `web`, …), null
 * for a device enrolled by a client that predates the field. The account zone shows it beside the
 * name, and it is the only thing that tells the browser session apart from an app one.
 */
@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
    val current: Boolean,
    val platform: String? = null,
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
