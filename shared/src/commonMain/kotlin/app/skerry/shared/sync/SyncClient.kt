package app.skerry.shared.sync

import kotlinx.coroutines.flow.Flow

/**
 * Device identity within an account (id is stable, generated on first login).
 * [platform] is a display label (e.g. "Android 34", "Linux") for the admin console; `null` means
 * "don't report" (the server keeps the already-known value).
 */
data class DeviceInfo(val id: String, val name: String, val platform: String? = null)

/** Active session with the sync server: accountId and a token pair (see design doc §4). */
data class SyncSession(val accountId: String, val accessToken: String, val refreshToken: String)

/**
 * Encrypted record as sent over the wire: metadata is plaintext, [blob] is XChaCha20-Poly1305
 * ciphertext (see `VaultRecord`). Client-side domain model; JSON/base64 serialization is a
 * network-layer detail. `equals`/`hashCode` are default (ByteArray by reference) — not used as a
 * collection key.
 */
data class RemoteRecord(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
)

/**
 * A delta page: records plus the new sync cursor (`lastSyncVersion`). [compactedIds] are tombstone
 * ids the server considers fully propagated (all devices have read them): the client physically
 * forgets them ([Vault.compact]) and stops pushing them, otherwise a re-push would resurrect them
 * after a server-side purge. Empty for an older server (the field is optional on the wire).
 */
data class RecordPage(val records: List<RemoteRecord>, val cursor: Long, val compactedIds: List<String> = emptyList())

data class RemoteDevice(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
    val current: Boolean,
)

/** Quick pairing ticket (variant B): a QR code and its expiry. */
data class PairingTicket(val code: String, val expiresAt: Long)

/** Result of claiming a pairing on a new device: encrypted dataKey plus a ready session. */
data class PairingResult(val accountId: String, val encryptedDataKey: ByteArray, val session: SyncSession)

/**
 * Client for the self-hosted sync server. Contract lives in the
 * core module; implementation is platform-specific (JVM: Ktor client + Nimbus SRP). Zero-knowledge:
 * only the [authKey]-derived SRP verifier and ciphertext blobs leave the device; password/masterKey/
 * dataKey never do. [authKey] is computed by the caller via `VaultCrypto.deriveAuthKey`.
 *
 * Network/protocol errors are signaled via [SyncException]; expected outcomes like "wrong
 * password/no account" are also [SyncException] with the matching [SyncException.Kind].
 */
interface SyncClient {

    /** Registers a new account: the client computes the SRP salt/verifier from [authKey]. */
    suspend fun register(
        accountId: String,
        authKey: ByteArray,
        wrappedDataKey: ByteArray,
        device: DeviceInfo,
    ): SyncSession

    /** Logs in via SRP (password never transmitted): challenge -> proof -> tokens. */
    suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession

    /** Wrapped dataKey to decrypt on this device (pairing variant A). */
    suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray

    /** Delta of records since server cursor `since`. */
    suspend fun pull(session: SyncSession, since: Long): RecordPage

    /** Batch upsert; response is the LWW-winning state and the new cursor. */
    suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage

    suspend fun listDevices(session: SyncSession): List<RemoteDevice>

    suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean

    /** Rotates tokens via refresh. */
    suspend fun refresh(session: SyncSession): SyncSession

    /** Starts pairing on the logged-in device: stores the encrypted transferKey-wrapped dataKey, returns a code. */
    suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket

    /** Claims a pairing on a new device by code (no login required). */
    suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult

    /** Stream of "changes available" signals (WS push). Completes when the connection closes. */
    fun changes(session: SyncSession): Flow<SyncSignal>

    /**
     * Lightweight server availability check (health probe `GET /healthz`, unauthenticated):
     * `true` if the server responded successfully. Deliberately does not throw — any network/
     * protocol failure yields `false`. Feeds a "server reachable" indicator that must work even
     * with a locked vault (there may be no active session).
     */
    suspend fun ping(): Boolean

    /** Releases network resources (HTTP/WS client). */
    suspend fun close()
}

/**
 * Live-sync signal from the `/sync` WS channel. Frames carry no content, only "what to reread":
 * `{cursor}` -> [Account]; `team:{id}:{cursor}` -> [Team]; `teams` -> [Membership].
 */
sealed interface SyncSignal {
    /** Changes appeared in the account vault up to [cursor] — do a delta pull. */
    data class Account(val cursor: Long) : SyncSignal

    /** Records appeared in team [teamId] up to [cursor] — sync the team vault. */
    data class Team(val teamId: String, val cursor: Long) : SyncSignal

    /** Account's team membership/invites changed — reread the team list. */
    data object Membership : SyncSignal
}

/** Sync client error: network, protocol, or expected (no account / wrong password). */
class SyncException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, UNAUTHORIZED, CONFLICT, NOT_FOUND, GONE, PROTOCOL, FORBIDDEN }
}
