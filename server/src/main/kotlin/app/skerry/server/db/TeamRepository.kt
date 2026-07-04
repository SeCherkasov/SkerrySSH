package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * Команды и их участники (Teams). Сервер — только доска объявлений: состав, роли и
 * запечатанные конверты приглашений; teamKey и содержимое записей ему недоступны.
 * ACL-проверки маршрутов опираются на [membership] (роль/статус).
 */
class TeamRepository(private val db: Database) {

    /** Публикует (или заменяет) публичный X25519-ключ аккаунта для приглашений. */
    suspend fun publishKey(accountId: String, publicKey: ByteArray, now: Long): Unit = newSuspendedTransaction(Dispatchers.IO, db) {
        val updated = AccountKeys.update({ AccountKeys.accountId eq accountId }) {
            it[AccountKeys.publicKey] = ExposedBlob(publicKey)
        }
        if (updated == 0) {
            AccountKeys.insert {
                it[AccountKeys.accountId] = accountId
                it[AccountKeys.publicKey] = ExposedBlob(publicKey)
                it[createdAt] = now
            }
        }
    }

    suspend fun publicKey(accountId: String): ByteArray? = newSuspendedTransaction(Dispatchers.IO, db) {
        AccountKeys.selectAll().where { AccountKeys.accountId eq accountId }
            .singleOrNull()?.get(AccountKeys.publicKey)?.bytes
    }

    /** Создаёт команду с владельцем-активным участником. false — id уже занят. */
    suspend fun create(teamId: String, ownerAccountId: String, now: Long): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        val exists = Teams.selectAll().where { Teams.id eq teamId }.any()
        if (exists) return@newSuspendedTransaction false
        Teams.insert {
            it[id] = teamId
            it[Teams.ownerAccountId] = ownerAccountId
            it[teamSeq] = 0
            it[createdAt] = now
        }
        TeamMembers.insert {
            it[TeamMembers.teamId] = teamId
            it[accountId] = ownerAccountId
            it[role] = TeamRoles.OWNER
            it[status] = TeamMemberStatus.ACTIVE
            it[envelope] = null
            it[invitedBy] = ownerAccountId
            it[createdAt] = now
        }
        true
    }

    /** Членства аккаунта (включая непринятые приглашения) с метаданными команд. */
    suspend fun teamsFor(accountId: String): List<TeamMembershipView> = newSuspendedTransaction(Dispatchers.IO, db) {
        val memberships = TeamMembers.selectAll().where { TeamMembers.accountId eq accountId }
            .map { it.toMemberRow() }
        memberships.mapNotNull { m ->
            val team = Teams.selectAll().where { Teams.id eq m.teamId }.singleOrNull()?.toTeamRow()
                ?: return@mapNotNull null
            val count = TeamMembers.selectAll().where { TeamMembers.teamId eq m.teamId }.count().toInt()
            TeamMembershipView(team, m.role, m.status, m.envelope, count)
        }
    }

    suspend fun members(teamId: String): List<TeamMemberRow> = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamMembers.selectAll().where { TeamMembers.teamId eq teamId }.map { it.toMemberRow() }
    }

    suspend fun membership(teamId: String, accountId: String): TeamMemberRow? = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamMembers.selectAll()
            .where { (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) }
            .singleOrNull()?.toMemberRow()
    }

    suspend fun team(teamId: String): TeamRow? = newSuspendedTransaction(Dispatchers.IO, db) {
        Teams.selectAll().where { Teams.id eq teamId }.singleOrNull()?.toTeamRow()
    }

    /** Приглашает аккаунт (status=invited, конверт с teamKey). false — уже участник/приглашён. */
    suspend fun invite(teamId: String, accountId: String, envelope: ByteArray, invitedBy: String, now: Long): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val exists = TeamMembers.selectAll()
                .where { (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) }
                .any()
            if (exists) return@newSuspendedTransaction false
            TeamMembers.insert {
                it[TeamMembers.teamId] = teamId
                it[TeamMembers.accountId] = accountId
                it[role] = TeamRoles.MEMBER
                it[status] = TeamMemberStatus.INVITED
                it[TeamMembers.envelope] = ExposedBlob(envelope)
                it[TeamMembers.invitedBy] = invitedBy
                it[createdAt] = now
            }
            true
        }

    /**
     * Принять приглашение: invited → active. Конверт очищается — после принятия teamKey живёт
     * в собственном vault участника и синхронизируется его аккаунтным синком.
     */
    suspend fun accept(teamId: String, accountId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamMembers.update({
            (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) and
                (TeamMembers.status eq TeamMemberStatus.INVITED)
        }) {
            it[status] = TeamMemberStatus.ACTIVE
            it[envelope] = null
        } > 0
    }

    /** Удаляет участника (отзыв доступа, отклонение приглашения или выход). Владельца не удаляет. */
    suspend fun removeMember(teamId: String, accountId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamMembers.deleteWhere {
            (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) and
                (TeamMembers.role neq TeamRoles.OWNER)
        } > 0
    }

    /** Удаляет команду целиком: записи, участников, саму команду. */
    suspend fun deleteTeam(teamId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamRecords.deleteWhere { TeamRecords.teamId eq teamId }
        TeamMembers.deleteWhere { TeamMembers.teamId eq teamId }
        Teams.deleteWhere { Teams.id eq teamId } > 0
    }

    /** id команд, где аккаунт — активный участник (для WS-подписок и ACL записей). */
    suspend fun activeTeamIdsFor(accountId: String): List<String> = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamMembers.selectAll()
            .where { (TeamMembers.accountId eq accountId) and (TeamMembers.status eq TeamMemberStatus.ACTIVE) }
            .map { it[TeamMembers.teamId] }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toMemberRow() = TeamMemberRow(
        teamId = this[TeamMembers.teamId],
        accountId = this[TeamMembers.accountId],
        role = this[TeamMembers.role],
        status = this[TeamMembers.status],
        envelope = this[TeamMembers.envelope]?.bytes,
        invitedBy = this[TeamMembers.invitedBy],
        createdAt = this[TeamMembers.createdAt],
    )

    private fun org.jetbrains.exposed.sql.ResultRow.toTeamRow() = TeamRow(
        id = this[Teams.id],
        ownerAccountId = this[Teams.ownerAccountId],
        teamSeq = this[Teams.teamSeq],
        createdAt = this[Teams.createdAt],
    )
}
