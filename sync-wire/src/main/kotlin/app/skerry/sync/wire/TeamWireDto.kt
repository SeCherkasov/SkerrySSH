package app.skerry.sync.wire

import kotlinx.serialization.EncodeDefault
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
    /**
     * Most recent activity across the member's devices (epoch millis), or null when the server
     * predates the field or the account has no device rows. Metadata the team already implies —
     * membership is public inside the team — and the only thing it says is whether a colleague is
     * still around.
     */
    val lastSeenAt: Long? = null,
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
 *
 * [recordId]/[recordType]/[scopeId] name the *subject* of a record event (`team.record_share`,
 * `team.record_change`, `team.record_remove`) or of a session event. Ids and types are metadata the
 * server already stores; the record's **name** is not here and never reaches the server — each
 * client resolves it from its own copy of the share space. Absent for events with no record subject
 * (membership, keys, and a bulk `team.push` summary). [durationSec] is set only on a reported
 * session recording. All four default to null so an older client keeps parsing the response.
 */
@Serializable
data class TeamActivityDto(
    val actorAccountId: String,
    val event: String,
    val detail: String,
    val createdAt: Long,
    val recordId: String? = null,
    val recordType: String? = null,
    val scopeId: String? = null,
    val durationSec: Long? = null,
)

@Serializable
data class TeamActivityResponse(
    val entries: List<TeamActivityDto>,
    // @EncodeDefault, or an empty log answers without a total at all — kotlinx omits a property
    // equal to its default. The reader would then page a list whose length it cannot see. The
    // default itself stays for reading a response from a server that predates the field.
    @EncodeDefault val total: Long = 0,
)

/**
 * A client's report that it opened a session on a shared record, or saved a recording of one
 * (`POST /teams/{id}/session-events`). [kind] is `open` or `record`; [durationSec] belongs to a
 * recording.
 *
 * Unlike everything else in the feed, this is **asserted by the client**, not observed by the
 * server: the server has no part in an SSH connection and cannot verify (or miss) one. A member who
 * turns reporting off simply reports nothing, so the session part of the feed is a collaboration
 * signal, not proof — the UI says so where it shows it.
 */
@Serializable
data class TeamSessionEventRequest(
    val recordId: String,
    val kind: String,
    val durationSec: Long? = null,
)

// --- scopes (granular sharing inside a team) ---

/**
 * A scope of a team (`GET /teams/{id}/scopes`). Granular sharing: records filed under a scope are
 * encrypted with that scope's own key and served only to accounts holding a grant, so a member
 * without the grant can neither fetch nor decrypt them.
 *
 * Zero-knowledge as everywhere else: the scope's **name** is not here — it travels inside
 * [envelope] alongside the key. [envelope] is the sealed current-epoch scopeKey for the calling
 * account (null without a grant) and, unlike an invite envelope, is kept after adoption: it is how a
 * client recovers a scope key its local vault record lost.
 */
@Serializable
data class TeamScopeDto(
    val scopeId: String,
    val keyEpoch: Long = 0,
    val memberCount: Int = 0,
    val envelope: String? = null,
)

@Serializable
data class TeamScopesResponse(val scopes: List<TeamScopeDto>)

/** Scope creation: [envelope] is the new scopeKey sealed to the creator themselves. */
@Serializable
data class TeamScopeCreateRequest(val scopeId: String, val envelope: String)

/** Grant of a scope to an account: [envelope] is the current-epoch scopeKey sealed to them. */
@Serializable
data class TeamScopeGrantRequest(val accountId: String, val envelope: String)

@Serializable
data class TeamScopeGrantDto(val accountId: String, val createdAt: Long)

@Serializable
data class TeamScopeGrantsResponse(val grants: List<TeamScopeGrantDto>)

// Team records reuse RecordDto/RecordsResponse/PushRequest/PushResponse: format is identical,
// only the scope changes (`/teams/{id}/records[?scope=]` instead of `/vault/records`).
