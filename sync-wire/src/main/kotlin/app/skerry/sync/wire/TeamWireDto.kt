package app.skerry.sync.wire

import kotlinx.serialization.Serializable

/**
 * Wire contract for Teams (record sharing between accounts, zero-knowledge).
 *
 * The server sees only metadata (team ids, membership, roles) and encrypted blobs:
 * - `publicKey` — public half of the account's X25519 pair (for sealing invitations);
 * - `envelope` — a crypto_box_seal sealed envelope with teamKey + team name, openable only by
 *   the invitee; server and admin cannot read the contents;
 * - `blob` in team records — XChaCha20-Poly1305 under teamKey (the server has no key).
 * The team name is never stored on the server at all — it travels in the envelope and in the
 * team record metadata.
 */

// --- account keys ---

/**
 * [publicKey] — X25519 half (seals invite/rekey envelopes). [signPublicKey] — Ed25519 half (signs
 * them, so a recipient can authenticate the author). Both are 32 bytes; neither is secret.
 */
@Serializable
data class PublishKeyRequest(val publicKey: String, val signPublicKey: String)

@Serializable
data class AccountKeyResponse(val accountId: String, val publicKey: String, val signPublicKey: String)

// --- teams and members ---

@Serializable
data class TeamCreateRequest(val teamId: String)

/** Roles: `owner` manages membership and deletes the team; both roles read/write records. */
@Serializable
data class TeamDto(
    val id: String,
    val ownerAccountId: String,
    val role: String,
    val status: String,
    val createdAt: Long,
    val memberCount: Int,
    /** Sealed invitation envelope for the current account; null after acceptance. */
    val envelope: String? = null,
    /** Current teamKey generation; bumped on every key rotation (member removal/demotion). */
    val keyEpoch: Long = 0,
    /**
     * Sealed current-epoch key envelope for the current member, delivered by a rotation. Same
     * signed format as [envelope]; the client adopts it only when its payload epoch is newer than
     * the locally stored one. Null when no rotation has happened since this member last had the key.
     */
    val keyEnvelope: String? = null,
)

@Serializable
data class TeamsResponse(val teams: List<TeamDto>)

@Serializable
data class TeamMemberDto(
    val accountId: String,
    val role: String,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class TeamMembersResponse(val members: List<TeamMemberDto>)

/**
 * Member invitation. [role] is the target role (`admin`/`editor`/`viewer`); the server rejects
 * `owner` and any role above the inviter's own. An empty/unknown role is treated as `viewer`.
 */
@Serializable
data class TeamInviteRequest(val accountId: String, val envelope: String, val role: String = "viewer")

/** Role change by owner/admin (`admin`/`editor`/`viewer`; `owner` not allowed). */
@Serializable
data class TeamRoleChangeRequest(val role: String)

/**
 * teamKey rotation (`POST /teams/{id}/rekey`). After removing/demoting a member the manager
 * generates a new teamKey and re-seals it to every remaining member; [envelopes] carries one signed
 * sealed key per member. [newEpoch] must equal the team's current epoch + 1 (monotonic). Zero
 * knowledge: the server stores the envelopes and the epoch but can't read the key.
 */
@Serializable
data class TeamRekeyRequest(val newEpoch: Long, val envelopes: List<RekeyEnvelopeDto>)

@Serializable
data class RekeyEnvelopeDto(val accountId: String, val envelope: String)

/**
 * Team audit entry (`GET /teams/{id}/activity`). Zero-knowledge: metadata only — actor, event,
 * and a human-readable summary ([detail], no record contents).
 */
@Serializable
data class TeamActivityDto(
    val actorAccountId: String,
    val event: String,
    val detail: String,
    val createdAt: Long,
)

@Serializable
data class TeamActivityResponse(val entries: List<TeamActivityDto>)

// Team records reuse RecordDto/RecordsResponse/PushRequest/PushResponse: format is identical,
// only the scope changes (`/teams/{id}/records` instead of `/vault/records`).
