package app.skerry.server.db

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

/** A scope as reported to one account: [envelope] is that account's sealed key, null without a grant. */
data class TeamScopeRow(
    val scopeId: String,
    val keyEpoch: Long,
    val memberCount: Int,
    val envelope: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TeamScopeRow) return false
        return scopeId == other.scopeId && keyEpoch == other.keyEpoch && memberCount == other.memberCount &&
            (envelope?.contentEquals(other.envelope) ?: (other.envelope == null))
    }

    override fun hashCode(): Int {
        var result = scopeId.hashCode()
        result = 31 * result + keyEpoch.hashCode()
        result = 31 * result + memberCount
        result = 31 * result + (envelope?.contentHashCode() ?: 0)
        return result
    }
}

/** One account's grant on a scope (manager view; the envelope stays private to its owner). */
data class TeamScopeGrantRow(val accountId: String, val createdAt: Long)

/**
 * Scopes of a team and their grants. Granular sharing is enforced on two independent levels: this
 * repository is the ACL the routes consult, and the scope key (which the server never sees) is what
 * actually keeps a scope's records unreadable to anyone without a grant.
 */
class TeamScopeRepository(private val db: Database) {

    /** Creates a scope and grants it to [accountId] ([envelope] = scopeKey sealed to them). False if it exists. */
    suspend fun create(teamId: String, scopeId: String, accountId: String, envelope: ByteArray, now: Long): Boolean =
        dbTransaction(db) {
            val exists = TeamScopes.selectAll()
                .where { (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId) }
                .any()
            if (exists) return@dbTransaction false
            TeamScopes.insert {
                it[TeamScopes.teamId] = teamId
                it[TeamScopes.scopeId] = scopeId
                it[keyEpoch] = 0
                it[createdAt] = now
            }
            insertGrant(teamId, scopeId, accountId, envelope, now)
            true
        }

    /** Deletes the scope with its grants and its records. */
    /**
     * Deletes the scope with its records and grants, and returns the accounts whose grant went with
     * it — `null` when there was no such scope.
     *
     * The grantees are read inside the transaction that removes them, and returned rather than fetched
     * by the caller beforehand: a grant added between a separate read and this delete is revoked in
     * the database but never announced, so that client keeps offering a scope it no longer holds until
     * something unrelated refreshes it. That closed a window as wide as an HTTP round trip.
     *
     * A narrow one is left, and deliberately: under PostgreSQL's default READ COMMITTED each statement
     * takes a fresh snapshot, so a [grant] committing between the SELECT below and the delete that
     * follows it is removed without appearing in the returned list. Locking does not help — the row is
     * inserted, not updated, so there is nothing to lock — and `DELETE ... RETURNING` is Postgres-only
     * in Exposed while SQLite is the default deployment. The consequence is bounded: access is revoked
     * either way (fail closed), only the live membership push is missed, and that client corrects
     * itself on its next refresh. On SQLite it cannot happen at all, though only because the pool is
     * capped at one connection.
     */
    suspend fun deleteReturningGrantees(teamId: String, scopeId: String): List<String>? = dbTransaction(db) {
        val grantees = TeamScopeGrants.selectAll()
            .where { (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) }
            .map { it[TeamScopeGrants.accountId] }
        TeamRecords.deleteWhere { (TeamRecords.teamId eq teamId) and (TeamRecords.scopeId eq scopeId) }
        TeamScopeGrants.deleteWhere { (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) }
        val removed = TeamScopes.deleteWhere {
            (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId)
        } > 0
        if (removed) grantees else null
    }

    /**
     * Scopes of the team as [accountId] may see them. With [all] (a manager) every scope is listed,
     * otherwise only the ones they hold a grant for — the mere existence of a scope already
     * describes how the team is organised.
     */
    suspend fun scopesFor(teamId: String, accountId: String, all: Boolean): List<TeamScopeRow> = dbTransaction(db) {
        val grants = TeamScopeGrants.selectAll().where { TeamScopeGrants.teamId eq teamId }.map {
            Triple(it[TeamScopeGrants.scopeId], it[TeamScopeGrants.accountId], it[TeamScopeGrants.envelope].bytes)
        }
        val counts = grants.groupingBy { it.first }.eachCount()
        val mine = grants.filter { it.second == accountId }.associate { it.first to it.third }
        TeamScopes.selectAll().where { TeamScopes.teamId eq teamId }
            .map { it[TeamScopes.scopeId] to it[TeamScopes.keyEpoch] }
            .filter { (scopeId, _) -> all || scopeId in mine }
            .map { (scopeId, epoch) -> TeamScopeRow(scopeId, epoch, counts[scopeId] ?: 0, mine[scopeId]) }
    }

    suspend fun exists(teamId: String, scopeId: String): Boolean = dbTransaction(db) {
        TeamScopes.selectAll().where { (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId) }.any()
    }

    suspend fun hasGrant(teamId: String, scopeId: String, accountId: String): Boolean = dbTransaction(db) {
        grantExists(teamId, scopeId, accountId)
    }

    suspend fun grants(teamId: String, scopeId: String): List<TeamScopeGrantRow> = dbTransaction(db) {
        TeamScopeGrants.selectAll()
            .where { (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) }
            .map { TeamScopeGrantRow(it[TeamScopeGrants.accountId], it[TeamScopeGrants.createdAt]) }
    }

    /** Grants (or re-seals) the scope to [accountId]. False if the scope doesn't exist. */
    suspend fun grant(teamId: String, scopeId: String, accountId: String, envelope: ByteArray, now: Long): Boolean =
        dbTransaction(db) {
            val scopeExists = TeamScopes.selectAll()
                .where { (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId) }
                .any()
            if (!scopeExists) return@dbTransaction false
            insertGrant(teamId, scopeId, accountId, envelope, now)
            true
        }

    suspend fun revoke(teamId: String, scopeId: String, accountId: String): Boolean = dbTransaction(db) {
        TeamScopeGrants.deleteWhere {
            (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) and
                (TeamScopeGrants.accountId eq accountId)
        } > 0
    }

    /**
     * Revokes every grant [accountId] held in the team (they were removed from the team itself) and
     * returns the affected scope ids — the caller rotates those keys, since a removed member keeps
     * their copy of every key they ever received.
     */
    suspend fun revokeAll(teamId: String, accountId: String): List<String> = dbTransaction(db) {
        val held = TeamScopeGrants.selectAll()
            .where { (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.accountId eq accountId) }
            .map { it[TeamScopeGrants.scopeId] }
        if (held.isNotEmpty()) {
            TeamScopeGrants.deleteWhere {
                (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.accountId eq accountId)
            }
        }
        held
    }

    /**
     * Rotates a scope's key: bumps its epoch to [newEpoch] and stores the re-sealed key per grantee.
     * Monotonicity is an atomic compare-and-set on the epoch, exactly as in [TeamRepository.rekey] —
     * two rotations racing to the same epoch would otherwise commit different keys at one epoch and
     * leave the scope unreadable. Envelopes for accounts without a grant are ignored: a rotation
     * must never resurrect access that was just revoked.
     */
    suspend fun rekey(teamId: String, scopeId: String, newEpoch: Long, envelopes: Map<String, ByteArray>): RekeyOutcome =
        dbTransaction(db) {
            val bumped = TeamScopes.update({
                (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId) and
                    (TeamScopes.keyEpoch eq newEpoch - 1)
            }) { it[keyEpoch] = newEpoch } > 0
            if (!bumped) {
                val exists = TeamScopes.selectAll()
                    .where { (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq scopeId) }
                    .any()
                return@dbTransaction if (exists) RekeyOutcome.EPOCH_CONFLICT else RekeyOutcome.NO_TEAM
            }
            envelopes.forEach { (accountId, envelope) ->
                TeamScopeGrants.update({
                    (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) and
                        (TeamScopeGrants.accountId eq accountId)
                }) {
                    it[TeamScopeGrants.envelope] = ExposedBlob(envelope)
                }
            }
            RekeyOutcome.OK
        }

    private fun grantExists(teamId: String, scopeId: String, accountId: String): Boolean =
        TeamScopeGrants.selectAll().where {
            (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) and
                (TeamScopeGrants.accountId eq accountId)
        }.any()

    /**
     * Upsert of a grant: re-granting replaces the sealed key instead of failing on the primary key.
     *
     * One statement rather than an UPDATE followed by an INSERT: two requests granting the same
     * (team, scope, account) for the first time — a second click, or a grant racing a rekey that also
     * lands here — would both see no row and both insert, and the loser hit the primary key and became
     * a 500. Retrying around the collision is not an option on Postgres, where a failed statement
     * aborts the transaction; `ON CONFLICT` avoids the collision instead of recovering from it, and
     * Exposed emits it for both SQLite and Postgres.
     *
     * Only the envelope is rewritten: [createdAt] is when the grant first appeared, and re-sealing a
     * key for someone who already holds the grant is not a new grant.
     */
    private fun insertGrant(teamId: String, scopeId: String, accountId: String, envelope: ByteArray, now: Long) {
        TeamScopeGrants.upsert(
            onUpdate = { it[TeamScopeGrants.envelope] = ExposedBlob(envelope) },
        ) {
            it[TeamScopeGrants.teamId] = teamId
            it[TeamScopeGrants.scopeId] = scopeId
            it[TeamScopeGrants.accountId] = accountId
            it[TeamScopeGrants.envelope] = ExposedBlob(envelope)
            it[createdAt] = now
        }
    }
}
