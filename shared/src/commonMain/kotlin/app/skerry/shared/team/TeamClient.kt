package app.skerry.shared.team

import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncSession

/**
 * Team role (hierarchy OWNER > ADMIN > EDITOR > VIEWER). Gates write/manage, not read — any active
 * member has the teamKey. Unknown string degrades to [VIEWER] (least privilege); legacy `member`
 * (could write records before granular roles) reads as [EDITOR].
 */
enum class TeamRole {
    OWNER, ADMIN, EDITOR, VIEWER;

    /** Manage membership: invite, remove, change roles. */
    val canManageMembers: Boolean get() = this == OWNER || this == ADMIN

    /** Write/share the team's shared records. */
    val canWrite: Boolean get() = this == OWNER || this == ADMIN || this == EDITOR

    /** View the team audit log. */
    val canViewAudit: Boolean get() = this == OWNER || this == ADMIN

    /** Roles this role may assign when inviting/changing (anti-escalation). */
    fun assignableRoles(): List<TeamRole> = when (this) {
        OWNER -> listOf(ADMIN, EDITOR, VIEWER)
        ADMIN -> listOf(EDITOR, VIEWER)
        else -> emptyList()
    }

    /** Wire/stored representation of the role. */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): TeamRole = when (value) {
            "owner" -> OWNER
            "admin" -> ADMIN
            "editor", "member" -> EDITOR
            else -> VIEWER
        }
    }
}

/** Team audit log entry: actor, event, human-readable summary (no record contents). */
class TeamActivityEntry(
    val actorAccountId: String,
    val event: String,
    val detail: String,
    val createdAt: Long,
)

/** Membership status. Unknown string degrades to [INVITED] (no record access). */
enum class TeamMemberStatus { INVITED, ACTIVE;
    companion object {
        fun fromWire(value: String): TeamMemberStatus = if (value == "active") ACTIVE else INVITED
    }
}

/** Team as seen by the current account: metadata + membership + invite/rekey envelopes. */
class TeamSummary(
    val id: String,
    val ownerAccountId: String,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val createdAt: Long,
    val memberCount: Int,
    val envelope: ByteArray?,
    /** Current teamKey generation; a rotation bumps it (see [TeamKeyEntry.epoch]). */
    val keyEpoch: Long = 0,
    /** Signed sealed current-epoch key from a rotation; the client adopts it when its epoch is newer. */
    val keyEnvelope: ByteArray? = null,
)

class TeamMember(
    val accountId: String,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val createdAt: Long,
)

/** An account's published Teams identity keys (both public halves; see [TeamClient.fetchPublicKey]). */
class AccountKeys(
    /** X25519 sharing key — seal invite/rekey envelopes to it. */
    val sharing: ByteArray,
    /** Ed25519 signing key — verify the account's invite/rekey signatures against it. */
    val signing: ByteArray,
)

/**
 * Teams network contract (`/account/key*`, `/teams*`) — stateless, all methods take [SyncSession].
 * Errors are [app.skerry.shared.sync.SyncException] with the same Kind as SyncClient.
 * Implemented by the same [app.skerry.shared.sync.SyncClient] transport (KtorSyncClient).
 */
interface TeamClient {
    /** Publishes the account identity's public halves (X25519 sharing key + Ed25519 signing key). */
    suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray)

    /** Another account's published keys; null if it hasn't enabled Teams yet (keys not published). */
    suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys?

    suspend fun createTeam(session: SyncSession, teamId: String)

    suspend fun listTeams(session: SyncSession): List<TeamSummary>

    suspend fun members(session: SyncSession, teamId: String): List<TeamMember>

    /** Invites [accountId] with role [role] (server rejects escalation above the inviter's rights). */
    suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray)

    suspend fun accept(session: SyncSession, teamId: String)

    /** Changes a member's role (owner/admin; server enforces anti-escalation, owner is immutable). */
    suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole)

    /**
     * Rotates the teamKey: bumps the team to [newEpoch] and stores one re-sealed key [envelopes]
     * per remaining member. Server enforces monotonicity (newEpoch == current + 1) and manage-members role.
     */
    suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>)

    /** Team audit log (owner/admin); newest events first. */
    suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry>

    /** Removes a member as owner, leaves the team, or declines an invite (target = self). */
    suspend fun removeMember(session: SyncSession, teamId: String, accountId: String)

    suspend fun deleteTeam(session: SyncSession, teamId: String)

    suspend fun pullTeam(session: SyncSession, teamId: String, since: Long): RecordPage

    suspend fun pushTeam(session: SyncSession, teamId: String, records: List<RemoteRecord>): RecordPage
}
