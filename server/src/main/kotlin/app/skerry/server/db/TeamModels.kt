package app.skerry.server.db

/** A team as the server sees it: metadata only, no name or key. */
data class TeamRow(
    val id: String,
    val ownerAccountId: String,
    val teamSeq: Long,
    val createdAt: Long,
)

/**
 * A team member. [envelope] is the sealed box carrying teamKey for the invitee (see [TeamMembers]);
 * cleared once the invite is accepted, after which teamKey lives in the member's own vault.
 */
data class TeamMemberRow(
    val teamId: String,
    val accountId: String,
    val role: String,
    val status: String,
    val envelope: ByteArray?,
    val invitedBy: String,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TeamMemberRow) return false
        return teamId == other.teamId && accountId == other.accountId && role == other.role &&
            status == other.status && invitedBy == other.invitedBy && createdAt == other.createdAt &&
            (envelope?.contentEquals(other.envelope ?: return false) ?: (other.envelope == null))
    }

    override fun hashCode(): Int {
        var result = teamId.hashCode()
        result = 31 * result + accountId.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (envelope?.contentHashCode() ?: 0)
        result = 31 * result + invitedBy.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/** Current account's membership plus team metadata; a `GET /teams` response row. */
data class TeamMembershipView(
    val team: TeamRow,
    val role: String,
    val status: String,
    val envelope: ByteArray?,
    val memberCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TeamMembershipView) return false
        return team == other.team && role == other.role && status == other.status &&
            memberCount == other.memberCount &&
            (envelope?.contentEquals(other.envelope ?: return false) ?: (other.envelope == null))
    }

    override fun hashCode(): Int {
        var result = team.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (envelope?.contentHashCode() ?: 0)
        result = 31 * result + memberCount
        return result
    }
}

/**
 * Member roles and statuses — the only valid values on the wire and in the DB, plus ACL
 * capability logic (single source of truth for routes). Granularity gates write/manage, not read:
 * any active member has teamKey, so a viewer that can't see secrets is cryptographically
 * impossible without key rotation. Role hierarchy: OWNER > ADMIN > EDITOR > VIEWER.
 */
object TeamRoles {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val EDITOR = "editor"
    const val VIEWER = "viewer"

    /** Pre-granular-roles legacy: any active member could write records, equivalent to EDITOR. */
    const val MEMBER = "member"

    /** Roles assignable via invite/change (OWNER is fixed to the creator). */
    val ASSIGNABLE = setOf(ADMIN, EDITOR, VIEWER)

    /** Manage members: invite, remove, change roles. */
    fun canManageMembers(role: String): Boolean = role == OWNER || role == ADMIN

    /** Write/share on the team: push/share/unshare records. */
    fun canWrite(role: String): Boolean = role == OWNER || role == ADMIN || role == EDITOR || role == MEMBER

    /** View the team's audit log. */
    fun canViewAudit(role: String): Boolean = role == OWNER || role == ADMIN

    /** Whether [actorRole] may assign/invite role [targetRole] (privilege-escalation guard). */
    fun canAssign(actorRole: String, targetRole: String): Boolean {
        if (targetRole !in ASSIGNABLE) return false
        return when (actorRole) {
            OWNER -> true
            ADMIN -> targetRole == EDITOR || targetRole == VIEWER
            else -> false
        }
    }

    /** Whether [actorRole] may remove/change a member with role [targetRole] (escalation guard). */
    fun canModifyMember(actorRole: String, targetRole: String): Boolean = when (actorRole) {
        OWNER -> targetRole != OWNER
        ADMIN -> targetRole == EDITOR || targetRole == VIEWER || targetRole == MEMBER
        else -> false
    }
}

object TeamMemberStatus {
    const val INVITED = "invited"
    const val ACTIVE = "active"
}
