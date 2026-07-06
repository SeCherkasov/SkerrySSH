package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Read and destructive operations needed only by the admin console: account aggregates, real
 * record envelopes, safe tombstone purge, and cascading account deletion. Zero-knowledge is
 * preserved — only metadata and ciphertext sizes are exposed, never content.
 */
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
     * Summary for all accounts on the instance. Aggregates are computed in the database (three
     * grouped queries, not N+1): devices (total/active/last seen) and records (total/tombstones/bytes).
     * `NOT revoked` / `CASE WHEN deleted` are portable between SQLite (0/1) and PostgreSQL (boolean).
     */
    suspend fun accountSummaries(limit: Int = 100): List<AccountSummary> = newSuspendedTransaction(Dispatchers.IO, db) {
        val devAgg = HashMap<String, DevAgg>()
        exec(
            """SELECT account_id,
                      COUNT(*) AS total,
                      SUM(CASE WHEN NOT revoked THEN 1 ELSE 0 END) AS active,
                      MAX(last_seen_at) AS last_seen
               FROM devices GROUP BY account_id""",
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
               FROM records GROUP BY account_id""",
        ) { rs ->
            while (rs.next()) {
                recAgg[rs.getString("account_id")] =
                    RecAgg(rs.getInt("total"), rs.getInt("tombstones"), rs.getLong("bytes"))
            }
        }

        Accounts.selectAll()
            .orderBy(Accounts.createdAt to SortOrder.ASC)
            .limit(limit)
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

    /** Total accounts on the instance, for an accurate "N of M" in the console. */
    suspend fun accountCount(): Long = newSuspendedTransaction(Dispatchers.IO, db) {
        Accounts.selectAll().count()
    }

    /**
     * Real record envelopes for an account (most recent by server cursor first, capped at
     * [limit]). [RecordEnvelope.previewHex] is the first 16 bytes of the actual ciphertext —
     * opaque noise demonstrating content is unreadable without the dataKey.
     */
    suspend fun recordEnvelopes(accountId: String, limit: Int = 100): List<RecordEnvelope> = newSuspendedTransaction(Dispatchers.IO, db) {
        Records.selectAll()
            .where { Records.accountId eq accountId }
            .orderBy(Records.serverSeq to SortOrder.DESC)
            .limit(limit)
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
    suspend fun purgeTombstones(accountId: String): Int = newSuspendedTransaction(Dispatchers.IO, db) {
        val watermark = tombstoneWatermark(accountId)
        Records.deleteWhere { propagatedTombstones(accountId, watermark) }
    }

    /**
     * Cascade-deletes an account with all its records, devices, and pairing sessions in one
     * transaction. The audit log is left untouched — it has no FK on [Accounts] and must survive
     * deletion (see [ActivityLog]). Returns false if the account doesn't exist.
     */
    suspend fun deleteAccount(accountId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        val exists = Accounts.selectAll().where { Accounts.id eq accountId }.any()
        if (!exists) return@newSuspendedTransaction false
        Records.deleteWhere { Records.accountId eq accountId }
        Pairing.deleteWhere { Pairing.accountId eq accountId }
        Devices.deleteWhere { Devices.accountId eq accountId }
        Accounts.deleteWhere { Accounts.id eq accountId }
        true
    }
}
