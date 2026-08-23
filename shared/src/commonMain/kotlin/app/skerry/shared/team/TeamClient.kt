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

/**
 * Team audit log entry: actor, event, human-readable summary (no record contents), and — for events
 * about one record — which record it was ([recordId], [recordType]) and the share space it lives in
 * ([scopeId], empty = team-wide). The record's **name** is deliberately absent: the server never
 * learns it, so each client resolves it from its own copy of the space (see [TeamActivityFeed]).
 */
class TeamActivityEntry(
    val actorAccountId: String,
    val event: String,
    val detail: String,
    val createdAt: Long,
    val recordId: String? = null,
    val recordType: String? = null,
    val scopeId: String? = null,
    /** Length of a reported session recording, in seconds. */
    val durationSec: Long? = null,
)

/** What a member reports about a session on a shared record (see [TeamClient.reportSessionEvent]). */
enum class TeamSessionKind(val wire: String) {
    /** A session to the shared host was opened. */
    OPEN("open"),

    /** A recording of a session on the shared host was saved. */
    RECORD("record"),
}

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
    /**
     * When the account was last active on any of its devices (epoch millis), or null on a server
     * that doesn't report it — the member table then shows nothing rather than inventing a time.
     */
    val lastSeenAt: Long? = null,
    /**
     * How many devices this member still has paired, as the server counts them — null on a server
     * that doesn't report it. The team's own device total is the sum over its active members, so a
     * single null makes that total unknown rather than an undercount.
     */
    val devices: Int? = null,
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

    /**
     * Reports a session on a record shared with the team, for the members' activity feed. Any active
     * member may report; the server derives the share space from the record itself and rejects a
     * record the team doesn't hold (or the caller can't see).
     *
     * Unverifiable by design — the server takes no part in an SSH connection — so this is a
     * collaboration signal rather than proof, and a member who turns reporting off simply sends
     * nothing.
     */
    suspend fun reportSessionEvent(
        session: SyncSession,
        teamId: String,
        recordId: String,
        kind: TeamSessionKind,
        durationSec: Long? = null,
    )

    /** Removes a member as owner, leaves the team, or declines an invite (target = self). */
    suspend fun removeMember(session: SyncSession, teamId: String, accountId: String)

    suspend fun deleteTeam(session: SyncSession, teamId: String)

    suspend fun pullTeam(session: SyncSession, ref: TeamScopeRef, since: Long): RecordPage

    suspend fun pushTeam(session: SyncSession, ref: TeamScopeRef, records: List<RemoteRecord>): RecordPage

    // --- scopes (granular sharing inside a team) ---

    /**
     * Scopes of the team. Managers see every scope; a plain member sees only the ones they hold a
     * grant for (a scope's existence is itself a hint about the team's structure).
     */
    suspend fun listScopes(session: SyncSession, teamId: String): List<TeamScopeSummary>

    /** Creates a scope and grants it to the creator ([envelope] = scopeKey sealed to themselves). */
    suspend fun createScope(session: SyncSession, teamId: String, scopeId: String, envelope: ByteArray)

    /** Deletes a scope with its grants and records (manage-members role). */
    suspend fun deleteScope(session: SyncSession, teamId: String, scopeId: String)

    /** Accounts holding a grant on the scope (manage-members role). */
    suspend fun scopeGrants(session: SyncSession, teamId: String, scopeId: String): List<TeamScopeGrantEntry>

    /** Grants [accountId] access to the scope, delivering the sealed current-epoch scopeKey. */
    suspend fun grantScope(session: SyncSession, teamId: String, scopeId: String, accountId: String, envelope: ByteArray)

    /** Revokes [accountId]'s grant. The caller rotates the scope key afterwards (forward secrecy). */
    suspend fun revokeScope(session: SyncSession, teamId: String, scopeId: String, accountId: String)

    /**
     * Rotates a scope's key: bumps it to [newEpoch] and stores one re-sealed key per remaining
     * grantee. Same monotonicity contract as [rekey], scoped to the scope's own epoch.
     */
    suspend fun rekeyScope(
        session: SyncSession,
        teamId: String,
        scopeId: String,
        newEpoch: Long,
        envelopes: Map<String, ByteArray>,
    )
}
