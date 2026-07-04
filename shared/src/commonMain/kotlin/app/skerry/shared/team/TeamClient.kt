package app.skerry.shared.team

import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncSession

/** Роль в команде. Незнакомая строка с сервера деградирует в [MEMBER] (меньше прав — безопаснее). */
enum class TeamRole { OWNER, MEMBER;
    companion object {
        fun fromWire(value: String): TeamRole = if (value == "owner") OWNER else MEMBER
    }
}

/** Статус членства. Незнакомая строка деградирует в [INVITED] (доступа к записям не даёт). */
enum class TeamMemberStatus { INVITED, ACTIVE;
    companion object {
        fun fromWire(value: String): TeamMemberStatus = if (value == "active") ACTIVE else INVITED
    }
}

/** Команда глазами текущего аккаунта: метаданные + membership + конверт приглашения (пока invited). */
class TeamSummary(
    val id: String,
    val ownerAccountId: String,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val createdAt: Long,
    val memberCount: Int,
    val envelope: ByteArray?,
)

class TeamMember(
    val accountId: String,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val createdAt: Long,
)

/**
 * Сетевой контракт Teams (`/account/key*`, `/teams*`) — стейтлесс, все методы принимают
 * [SyncSession]. Ошибки — [app.skerry.shared.sync.SyncException] с теми же Kind, что у SyncClient.
 * Реализуется тем же [app.skerry.shared.sync.SyncClient]-транспортом (KtorSyncClient).
 */
interface TeamClient {
    /** Публикует публичную X25519-половину identity-пары аккаунта. */
    suspend fun publishKey(session: SyncSession, publicKey: ByteArray)

    /** Публичный ключ другого аккаунта; null — аккаунт ещё не включал Teams (ключ не опубликован). */
    suspend fun fetchPublicKey(session: SyncSession, accountId: String): ByteArray?

    suspend fun createTeam(session: SyncSession, teamId: String)

    suspend fun listTeams(session: SyncSession): List<TeamSummary>

    suspend fun members(session: SyncSession, teamId: String): List<TeamMember>

    suspend fun invite(session: SyncSession, teamId: String, accountId: String, envelope: ByteArray)

    suspend fun accept(session: SyncSession, teamId: String)

    /** Удаление участника владельцем, выход из команды или отклонение приглашения (target = сам). */
    suspend fun removeMember(session: SyncSession, teamId: String, accountId: String)

    suspend fun deleteTeam(session: SyncSession, teamId: String)

    suspend fun pullTeam(session: SyncSession, teamId: String, since: Long): RecordPage

    suspend fun pushTeam(session: SyncSession, teamId: String, records: List<RemoteRecord>): RecordPage
}
