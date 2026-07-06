package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/** Vault record type — matches the sync model (`docs/skerry-sync-design.md` §2). */
@Serializable
enum class RecordType {
    HOST, GROUP, IDENTITY, CREDENTIAL, KNOWN_HOST, SNIPPET, TUNNEL, SETTINGS, TERMINAL_HISTORY,

    /** Key and metadata of one team (teamKey + name + role) — in the member's own vault. */
    TEAM,

    /** Account X25519 pair for receiving Teams invites (singleton; public half is on the server). */
    TEAM_IDENTITY,
}

/**
 * A local vault record in its encrypted on-disk form. Metadata (`id`, `type`, `version`,
 * `updatedAt`, `deviceId`, `deleted`) is stored in plaintext; `blob` is
 * XChaCha20-Poly1305(dataKey, payload) with AAD bound to `id‖type` (prevents swapping records
 * between slots). The same structure is the unit of E2E sync, so `version` is a Lamport counter for
 * LWW and deletion is a tombstone (`deleted=true`), not physical erasure.
 *
 * `blob` is serialized by kotlinx.serialization. `equals`/`hashCode` are default (reference-based
 * for `ByteArray`) — this is a value container, not a collection key; compare by field in tests.
 */
@Serializable
data class VaultRecord(
    val id: String,
    val type: RecordType,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
)
