package app.skerry.shared.sync

import kotlinx.serialization.Serializable

/**
 * JSON-контракт «на проводе» к sync-серверу — зеркало `server/.../model/Dto.kt`. Живёт в
 * jvmShared (деталь сетевой реализации), а не в ядре: commonMain оперирует доменными
 * [RemoteRecord]/[SyncSession], не зная о base64/JSON. Единство контракта проверяет e2e-тест.
 */
@Serializable
internal data class RegisterRequestWire(
    val accountId: String,
    val srpSalt: String,
    val srpVerifier: String,
    val wrappedDataKey: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String? = null,
)

@Serializable
internal data class ChallengeRequestWire(val accountId: String)

@Serializable
internal data class ChallengeResponseWire(val challengeId: String, val salt: String, val b: String)

@Serializable
internal data class VerifyRequestWire(
    val challengeId: String,
    val a: String,
    val m1: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String? = null,
)

@Serializable
internal data class VerifyResponseWire(val m2: String, val accessToken: String, val refreshToken: String)

@Serializable
internal data class RefreshRequestWire(val refreshToken: String)

@Serializable
internal data class TokenResponseWire(val accessToken: String, val refreshToken: String)

@Serializable
internal data class KeysResponseWire(val wrappedDataKey: String)

@Serializable
internal data class RecordDtoWire(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: String,
)

@Serializable
internal data class RecordsResponseWire(
    val records: List<RecordDtoWire>,
    val cursor: Long,
    val compactedIds: List<String> = emptyList(),
)

@Serializable
internal data class PushRequestWire(val records: List<RecordDtoWire>)

@Serializable
internal data class PushResponseWire(val records: List<RecordDtoWire>, val cursor: Long)

@Serializable
internal data class DeviceDtoWire(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
    val current: Boolean,
)

@Serializable
internal data class DevicesResponseWire(val devices: List<DeviceDtoWire>)

@Serializable
internal data class PairingStartRequestWire(val encryptedDataKey: String, val ttlSeconds: Long? = null)

@Serializable
internal data class PairingStartResponseWire(val code: String, val expiresAt: Long)

@Serializable
internal data class PairingClaimRequestWire(val code: String, val deviceId: String, val deviceName: String)

@Serializable
internal data class PairingClaimResponseWire(
    val accountId: String,
    val encryptedDataKey: String,
    val accessToken: String,
    val refreshToken: String,
)
