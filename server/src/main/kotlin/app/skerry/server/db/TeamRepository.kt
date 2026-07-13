package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update

/** Outcome of [TeamRepository.rekey]: success, a lost monotonicity race / stale epoch, or a gone team. */
enum class RekeyOutcome { OK, EPOCH_CONFLICT, NO_TEAM }

/**
 * Teams and their members. The server only tracks membership, roles, and sealed invite
 * envelopes; it never sees teamKey or record contents. Route ACL checks rely on
 * [membership] (role/status).
 */
class TeamRepository(private val db: Database) {

    /** Publishes (or replaces) an account's public identity keys (X25519 sharing + Ed25519 signing). */
    suspend fun publishKey(accountId: String, publicKey: ByteArray, signPublicKey: ByteArray, now: Long): Unit = newSuspendedTransaction(Dispatchers.IO, db) {
        val updated = AccountKeys.update({ AccountKeys.accountId eq accountId }) {
            it[AccountKeys.publicKey] = ExposedBlob(publicKey)
            it[AccountKeys.signPublicKey] = ExposedBlob(signPublicKey)
        }
        if (updated == 0) {
            AccountKeys.insert {
                it[AccountKeys.accountId] = accountId
                it[AccountKeys.publicKey] = ExposedBlob(publicKey)
                it[AccountKeys.signPublicKey] = ExposedBlob(signPublicKey)
                it[createdAt] = now
            }
        }
    }

    suspend fun accountKeys(accountId: String): AccountKeysRow? = newSuspendedTransaction(Dispatchers.IO, db) {
        AccountKeys.selectAll().where { AccountKeys.accountId eq accountId }.singleOrNull()?.let {
            AccountKeysRow(it[AccountKeys.publicKey].bytes, it[AccountKeys.signPublicKey]?.bytes)
        }
    }

    /** Creates a team with the owner as an active member. Returns false if the id is taken. */
    suspend fun create(teamId: String, ownerAccountId: String, now: Long): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        val exists = Teams.selectAll().where { Teams.id eq teamId }.any()
        if (exists) return@newSuspendedTransaction false
        Teams.insert {
            it[id] = teamId
            it[Teams.ownerAccountId] = ownerAccountId
            it[teamSeq] = 0
            it[keyEpoch] = 0
            it[createdAt] = now
        }
        TeamMembers.insert {
            it[TeamMembers.teamId] = teamId
            it[accountId] = ownerAccountId
            it[role] = TeamRoles.OWNER
            it[status] = TeamMemberStatus.ACTIVE
            it[envelope] = null
            it[keyEnvelope] = null
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
            TeamMembershipView(team, m.role, m.status, m.envelope, m.keyEnvelope, count)
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
                it[TeamMembers.keyEnvelope] = null
                it[TeamMembers.invitedBy] = invitedBy
                it[createdAt] = now
            }
            true
        }

    /**
     * Rotates the team's key: bumps [Teams.keyEpoch] to [newEpoch] and stores the re-sealed key
     * ([envelopes]: accountId -> sealed blob) on each covered member. Members not present in
     * [envelopes] keep their previous keyEnvelope (they will be stuck on the old epoch — the caller
     * covers everyone).
     *
     * Monotonicity is enforced atomically here, not at the route: the epoch bump is a compare-and-set
     * (`UPDATE ... WHERE keyEpoch = newEpoch - 1`). Two concurrent rotations racing to the same
     * [newEpoch] both target `keyEpoch == newEpoch - 1`, but the row lock serializes them — the loser
     * re-evaluates the predicate against the already-bumped epoch, matches 0 rows, and gets
     * [RekeyOutcome.EPOCH_CONFLICT]. Without the predicate a route-level read-then-write TOCTOU would
     * let both commit different keys at one epoch (split-brain: records unreadable). Returns
     * [RekeyOutcome.NO_TEAM] if the team is gone.
     */
    suspend fun rekey(teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>): RekeyOutcome =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val bumped = Teams.update({
                (Teams.id eq teamId) and (Teams.keyEpoch eq newEpoch - 1)
            }) { it[keyEpoch] = newEpoch } > 0
            if (!bumped) {
                val exists = Teams.selectAll().where { Teams.id eq teamId }.any()
                return@newSuspendedTransaction if (exists) RekeyOutcome.EPOCH_CONFLICT else RekeyOutcome.NO_TEAM
            }
            envelopes.forEach { (accountId, envelope) ->
                TeamMembers.update({
                    (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId)
                }) {
                    it[keyEnvelope] = ExposedBlob(envelope)
                }
            }
            RekeyOutcome.OK
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

    private fun org.jetbrains.exposed.v1.core.ResultRow.toMemberRow() = TeamMemberRow(
        teamId = this[TeamMembers.teamId],
        accountId = this[TeamMembers.accountId],
        role = this[TeamMembers.role],
        status = this[TeamMembers.status],
        envelope = this[TeamMembers.envelope]?.bytes,
        keyEnvelope = this[TeamMembers.keyEnvelope]?.bytes,
        invitedBy = this[TeamMembers.invitedBy],
        createdAt = this[TeamMembers.createdAt],
    )

    private fun org.jetbrains.exposed.v1.core.ResultRow.toTeamRow() = TeamRow(
        id = this[Teams.id],
        ownerAccountId = this[Teams.ownerAccountId],
        teamSeq = this[Teams.teamSeq],
        keyEpoch = this[Teams.keyEpoch],
        createdAt = this[Teams.createdAt],
    )
}
