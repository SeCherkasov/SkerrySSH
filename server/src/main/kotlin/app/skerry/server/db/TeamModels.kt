package app.skerry.server.db

/** Команда в том виде, как её видит сервер: только метаданные, имени и ключа у сервера нет. */
data class TeamRow(
    val id: String,
    val ownerAccountId: String,
    val teamSeq: Long,
    val createdAt: Long,
)

/**
 * Участник команды. [envelope] — sealed-конверт с teamKey для приглашённого (см. [TeamMembers]);
 * очищается при принятии приглашения: дальше teamKey живёт в собственном vault участника.
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

/** Членство текущего аккаунта + метаданные команды — строка ответа `GET /teams`. */
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

/** Роли и статусы участников — единственные допустимые значения на проводе и в БД. */
object TeamRoles {
    const val OWNER = "owner"
    const val MEMBER = "member"
}

object TeamMemberStatus {
    const val INVITED = "invited"
    const val ACTIVE = "active"
}
