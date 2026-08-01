package app.skerry.server.db

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Read and destructive operations needed only by the admin console: account aggregates, real
 * record envelopes, safe tombstone purge, and cascading account deletion. Zero-knowledge is
 * preserved — only metadata and ciphertext sizes are exposed, never content.
 */
/**
 * What an account deletion did to the teams it owned — the part that touches **other** people's
 * data, so it belongs in the audit line rather than staying invisible.
 */
data class AccountDeletion(
    val teamsTransferred: List<String>,
    val teamsDeleted: List<String>,
    /** New owner per transferred team, for the audit line and the feed entry members actually read. */
    val newOwners: Map<String, String> = emptyMap(),
    /**
     * Everyone still in an affected team. They learn about a forced transfer or a deleted team only
     * if the server pushes — every other membership change publishes, so this one must too.
     */
    val notifyAccounts: List<String> = emptyList(),
)

class AdminRepository(private val db: Database) {

    data class AccountSummary(
        val id: String,
        val createdAt: Long,
        val syncSeq: Long,
        val devices: Int,
        val activeDevices: Int,
        val records: Int,
        val tombstones: Int,
        val storageBytes: Long,
        val lastSeenAt: Long?,
    )

    data class RecordEnvelope(
        val id: String,
        val type: String,
        val version: Long,
        val updatedAt: String,
        val deviceId: String,
        val deleted: Boolean,
        val blobBytes: Int,
        val serverSeq: Long,
        val previewHex: String,
    )

    private class DevAgg(var total: Int = 0, var active: Int = 0, var lastSeen: Long? = null)
    private class RecAgg(var total: Int = 0, var tombstones: Int = 0, var bytes: Long = 0)

    /**
     * The same aggregates for one account, for the account zone's own Overview. Deliberately the
     * same query as the console's: two zones reporting different numbers for one account would be a
     * bug nobody could reproduce. Null if the account doesn't exist.
     */
    suspend fun accountSummary(accountId: String): AccountSummary? =
        accountSummaries(limit = 1, accountId = accountId).firstOrNull()

    /**
     * Summary for all accounts on the instance, or for [accountId] alone. Aggregates are computed in
     * the database (three grouped queries, not N+1): devices (total/active/last seen) and records
     * (total/tombstones/bytes). `NOT revoked` / `CASE WHEN deleted` are portable between SQLite (0/1)
     * and PostgreSQL (boolean).
     */
    suspend fun accountSummaries(limit: Int = 100, accountId: String? = null, offset: Long = 0): List<AccountSummary> = dbTransaction(db) {
        // Bound parameter, never interpolation: the account zone reaches this with an id it took
        // from a JWT, and one route away from that is a browser-supplied string.
        val scope = if (accountId == null) "" else " WHERE account_id = ?"
        val args: List<Pair<IColumnType<*>, Any?>> =
            if (accountId == null) emptyList() else listOf(VarCharColumnType(ACCOUNT_ID_LENGTH) to accountId)

        val devAgg = HashMap<String, DevAgg>()
        exec(
            """SELECT account_id,
                      COUNT(*) AS total,
                      SUM(CASE WHEN NOT revoked THEN 1 ELSE 0 END) AS active,
                      MAX(last_seen_at) AS last_seen
               FROM devices$scope GROUP BY account_id""",
            args,
            StatementType.SELECT,
        ) { rs ->
            while (rs.next()) {
                val a = DevAgg(rs.getInt("total"), rs.getInt("active"))
                a.lastSeen = rs.getLong("last_seen").let { if (rs.wasNull()) null else it }
                devAgg[rs.getString("account_id")] = a
            }
        }

        val recAgg = HashMap<String, RecAgg>()
        exec(
            """SELECT account_id,
                      COUNT(*) AS total,
                      SUM(CASE WHEN deleted THEN 1 ELSE 0 END) AS tombstones,
                      COALESCE(SUM(LENGTH(blob)), 0) AS bytes
               FROM records$scope GROUP BY account_id""",
            args,
            StatementType.SELECT,
        ) { rs ->
            while (rs.next()) {
                recAgg[rs.getString("account_id")] =
                    RecAgg(rs.getInt("total"), rs.getInt("tombstones"), rs.getLong("bytes"))
            }
        }

        val rows = Accounts.selectAll()
        if (accountId != null) rows.andWhere { Accounts.id eq accountId }
        rows.orderBy(Accounts.createdAt to SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { row ->
                val id = row[Accounts.id]
                val d = devAgg[id] ?: DevAgg()
                val r = recAgg[id] ?: RecAgg()
                AccountSummary(
                    id = id,
                    createdAt = row[Accounts.createdAt],
                    syncSeq = row[Accounts.syncSeq],
                    devices = d.total,
                    activeDevices = d.active,
                    records = r.total,
                    tombstones = r.tombstones,
                    storageBytes = r.bytes,
                    lastSeenAt = d.lastSeen,
                )
            }
    }

    /**
     * Records held for one account: the same predicate [recordEnvelopes] pages over, so the total
     * counts that list and no other. It is read in its own transaction, so a write landing between
     * the two can leave the count a row ahead of the page until the next read — a stale number, not
     * a wrong list.
     */
    suspend fun recordCount(accountId: String): Long = dbTransaction(db) {
        Records.selectAll().where { Records.accountId eq accountId }.count()
    }

    /** Total accounts on the instance, for an accurate "N of M" in the console. */
    suspend fun accountCount(): Long = dbTransaction(db) {
        Accounts.selectAll().count()
    }

    /**
     * Real record envelopes for an account (most recent by server cursor first, capped at
     * [limit]). [RecordEnvelope.previewHex] is the first 16 bytes of the actual ciphertext —
     * opaque noise demonstrating content is unreadable without the dataKey.
     */
    suspend fun recordEnvelopes(accountId: String, limit: Int = 100, offset: Long = 0): List<RecordEnvelope> = dbTransaction(db) {
        Records.selectAll()
            .where { Records.accountId eq accountId }
            .orderBy(Records.serverSeq to SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { row ->
                val bytes = row[Records.blob].bytes
                RecordEnvelope(
                    id = row[Records.recordId],
                    type = row[Records.type],
                    version = row[Records.version],
                    updatedAt = row[Records.updatedAt],
                    deviceId = row[Records.deviceId],
                    deleted = row[Records.deleted],
                    blobBytes = bytes.size,
                    serverSeq = row[Records.serverSeq],
                    previewHex = bytes.take(16).joinToString(" ") { b -> "%02x".format(b) },
                )
            }
    }

    /**
     * Physically deletes account tombstones already propagated to all devices — same criterion,
     * [propagatedTombstones] over [tombstoneWatermark], as [RecordRepository.compactedTombstoneIds].
     * Returns the number of tombstones deleted.
     */
    suspend fun purgeTombstones(accountId: String): Int = dbTransaction(db) {
        val watermark = tombstoneWatermark(accountId)
        Records.deleteWhere { propagatedTombstones(accountId, watermark) }
    }

    /**
     * Cascade-deletes an account in one transaction: its records, devices, pairing sessions,
     * published Teams keys, memberships and scope grants — every column that names it, so nothing is
     * left pointing at an id that no longer exists. That is not cosmetic: SQLite doesn't enforce
     * foreign keys, so leftovers rot silently there, while on PostgreSQL the `accounts` delete itself
     * fails as long as any of them remain.
     *
     * Teams the account **owns** can't simply be orphaned — a team with no owner can't be managed or
     * rekeyed by anyone. Ownership passes to the most senior active member ([heirOf]); with no active
     * member left the team is deleted with all of its records, scopes and grants.
     *
     * The audit log is left untouched — it has no FK on [Accounts] and must survive deletion
     * (see [ActivityLog]). Returns false if the account doesn't exist.
     */
    suspend fun deleteAccount(accountId: String): AccountDeletion? = dbTransaction(db) {
        val exists = Accounts.selectAll().where { Accounts.id eq accountId }.any()
        if (!exists) return@dbTransaction null
        val transferred = mutableListOf<String>()
        val deletedTeams = mutableListOf<String>()
        val newOwners = mutableMapOf<String, String>()
        val notify = mutableSetOf<String>()

        Teams.selectAll().where { Teams.ownerAccountId eq accountId }.map { it[Teams.id] }.forEach { teamId ->
            // Collected before the rows go away: after the transaction there is no query left that
            // could tell who was in a deleted team, or who now owns a transferred one.
            notify += TeamMembers.selectAll()
                .where { (TeamMembers.teamId eq teamId) and (TeamMembers.accountId neq accountId) }
                .map { it[TeamMembers.accountId] }
            val heir = heirOf(teamId, accountId)
            if (heir == null) {
                deletedTeams += teamId
                TeamRecords.deleteWhere { TeamRecords.teamId eq teamId }
                TeamScopeGrants.deleteWhere { TeamScopeGrants.teamId eq teamId }
                TeamScopes.deleteWhere { TeamScopes.teamId eq teamId }
                TeamMembers.deleteWhere { TeamMembers.teamId eq teamId }
                Teams.deleteWhere { Teams.id eq teamId }
            } else {
                transferred += teamId
                newOwners[teamId] = heir
                Teams.update({ Teams.id eq teamId }) { it[ownerAccountId] = heir }
                TeamMembers.update({ (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq heir) }) {
                    it[role] = TeamRoles.OWNER
                }
            }
        }

        TeamScopeGrants.deleteWhere { TeamScopeGrants.accountId eq accountId }
        TeamMembers.deleteWhere { TeamMembers.accountId eq accountId }
        AccountKeys.deleteWhere { AccountKeys.accountId eq accountId }
        Records.deleteWhere { Records.accountId eq accountId }
        Pairing.deleteWhere { Pairing.accountId eq accountId }
        Devices.deleteWhere { Devices.accountId eq accountId }
        Accounts.deleteWhere { Accounts.id eq accountId }
        AccountDeletion(transferred, deletedTeams, newOwners, notify.toList())
    }

    /**
     * Who inherits [teamId] when its owner is deleted: the most senior **active** member, oldest
     * membership first on a tie. Invited-but-not-accepted members are not candidates — they never
     * adopted the team key, so handing them the team would hand them something they can't open.
     */
    private fun heirOf(teamId: String, leavingAccountId: String): String? =
        TeamMembers.selectAll()
            .where {
                (TeamMembers.teamId eq teamId) and
                    (TeamMembers.accountId neq leavingAccountId) and
                    (TeamMembers.status eq TeamMemberStatus.ACTIVE)
            }
            .minWithOrNull(
                compareBy({ SUCCESSION.indexOf(it[TeamMembers.role]).takeIf { i -> i >= 0 } ?: SUCCESSION.size }, { it[TeamMembers.createdAt] }),
            )
            ?.get(TeamMembers.accountId)

    private companion object {
        /** Succession order for an orphaned team; an unknown role sorts last but still qualifies. */
        val SUCCESSION = listOf(TeamRoles.ADMIN, TeamRoles.EDITOR, TeamRoles.MEMBER, TeamRoles.VIEWER)

        /** Mirrors `varchar(320)` in [Accounts.id]; the bound-parameter type for the scoped queries. */
        const val ACCOUNT_ID_LENGTH = 320
    }
}
