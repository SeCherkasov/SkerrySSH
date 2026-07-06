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
 * Teams and their members. The server only tracks membership, roles, and sealed invite
 * envelopes; it never sees teamKey or record contents. Route ACL checks rely on
 * [membership] (role/status).
 */
class TeamRepository(private val db: Database) {

    /** Publishes (or replaces) an account's public X25519 key used for invitations. */
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

    /** Creates a team with the owner as an active member. Returns false if the id is taken. */
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

    /** Account memberships (including unaccepted invites) with team metadata. */
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

    /** Invites an account with role [role] (status=invited, envelope carries teamKey). False if already a member/invited. */
    suspend fun invite(teamId: String, accountId: String, role: String, envelope: ByteArray, invitedBy: String, now: Long): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val exists = TeamMembers.selectAll()
                .where { (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) }
                .any()
            if (exists) return@newSuspendedTransaction false
            TeamMembers.insert {
                it[TeamMembers.teamId] = teamId
                it[TeamMembers.accountId] = accountId
                it[TeamMembers.role] = role
                it[status] = TeamMemberStatus.INVITED
                it[TeamMembers.envelope] = ExposedBlob(envelope)
                it[TeamMembers.invitedBy] = invitedBy
                it[createdAt] = now
            }
            true
        }

    /** Changes a member's role (owner's role cannot change). False if member missing or is the owner. */
    suspend fun updateRole(teamId: String, accountId: String, role: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db) {
            TeamMembers.update({
                (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) and
                    (TeamMembers.role neq TeamRoles.OWNER)
            }) {
                it[TeamMembers.role] = role
            } > 0
        }

    /**
     * Accepts an invite: invited -> active. The envelope is cleared; after acceptance teamKey
     * lives in the member's own vault and syncs via their account sync.
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

    /** Removes a member (access revocation, invite decline, or leave). Never removes the owner. */
    suspend fun removeMember(teamId: String, accountId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamMembers.deleteWhere {
            (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId) and
                (TeamMembers.role neq TeamRoles.OWNER)
        } > 0
    }

    /** Deletes a team entirely: records, members, and the team itself. */
    suspend fun deleteTeam(teamId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamRecords.deleteWhere { TeamRecords.teamId eq teamId }
        TeamMembers.deleteWhere { TeamMembers.teamId eq teamId }
        Teams.deleteWhere { Teams.id eq teamId } > 0
    }

    /** Ids of teams where the account is an active member (for WS subscriptions and record ACLs). */
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
