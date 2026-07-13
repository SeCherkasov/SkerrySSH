package app.skerry.server.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * A record received from the client, not yet assigned a server cursor.
 *
 * equals/hashCode are overridden manually: the data-class default would compare [blob] by
 * reference, making two structurally identical records compare unequal.
 */
class IncomingRecord(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingRecord) return false
        return id == other.id && type == other.type && version == other.version &&
            updatedAt == other.updatedAt && deviceId == other.deviceId &&
            deleted == other.deleted && blob.contentEquals(other.blob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + deleted.hashCode()
        result = 31 * result + blob.contentHashCode()
        return result
    }
}

/**
 * Result of a batch upsert: winning records (input order) plus the new account cursor. [changed]
 * is true if any record won LWW and the cursor advanced; PUT uses it to decide whether to send a
 * WS signal (a no-op push does not publish, to avoid a push -> WS -> push loop).
 */
data class UpsertResult(val records: List<StoredRecord>, val cursor: Long, val changed: Boolean)

/**
 * Encrypted vault records. Implements LWW conflict resolution
 * and delta selection by server cursor.
 *
 * [lockAccountRow] = true (PostgreSQL) takes `SELECT ... FOR UPDATE` on the account row, serializing
 * concurrent upserts so two transactions can't assign the same `serverSeq`. Not needed for SQLite
 * (pool=1, single writer).
 */
class RecordRepository(private val db: Database, private val lockAccountRow: Boolean = false) {

    /**
     * Batch upsert with LWW. For each incoming record, the larger of (`version`, then `deviceId`
     * lexicographically) wins; otherwise the server version is kept. Returns each record's winning
     * state (with assigned `serverSeq`) and the final cursor, so the client can tell which of its
     * changes were rejected and advance `since` from the cursor.
     */
    suspend fun upsert(accountId: String, incoming: List<IncomingRecord>): UpsertResult = dbTransaction(db) {
        val accountQuery = Accounts.selectAll().where { Accounts.id eq accountId }
        // Compare against the locally captured seqBefore, not a re-read from the DB: under
        // READ COMMITTED a re-SELECT would see a concurrent commit and regress the cursor.
        val seqBefore = (if (lockAccountRow) accountQuery.forUpdate() else accountQuery).single()[Accounts.syncSeq]
        var seq = seqBefore

        val result = incoming.map { rec ->
            val existing = Records.selectAll()
                .where { (Records.accountId eq accountId) and (Records.recordId eq rec.id) }
                .singleOrNull()

            val wins = existing == null ||
                rec.version > existing[Records.version] ||
                (rec.version == existing[Records.version] && rec.deviceId > existing[Records.deviceId])

            if (wins) {
                seq += 1
                val newSeq = seq
                if (existing == null) {
                    Records.insert {
                        it[Records.accountId] = accountId
                        it[recordId] = rec.id
                        it[type] = rec.type
                        it[version] = rec.version
                        it[updatedAt] = rec.updatedAt
                        it[deviceId] = rec.deviceId
                        it[deleted] = rec.deleted
                        it[blob] = ExposedBlob(rec.blob)
                        it[serverSeq] = newSeq
                    }
                } else {
                    Records.update({ (Records.accountId eq accountId) and (Records.recordId eq rec.id) }) {
                        it[type] = rec.type
                        it[version] = rec.version
                        it[updatedAt] = rec.updatedAt
                        it[deviceId] = rec.deviceId
                        it[deleted] = rec.deleted
                        it[blob] = ExposedBlob(rec.blob)
                        it[serverSeq] = newSeq
                    }
                }
                StoredRecord(rec.id, rec.type, rec.version, rec.updatedAt, rec.deviceId, rec.deleted, rec.blob, newSeq)
            } else {
                existing.toStoredRecord()
            }
        }

        val changed = seq != seqBefore
        if (changed) {
            Accounts.update({ Accounts.id eq accountId }) { it[syncSeq] = seq }
        }
        UpsertResult(result, seq, changed)
    }

    /**
     * IDs of the account's tombstones already propagated to all devices, via [propagatedTombstones]
     * against [tombstoneWatermark] (shared with [AdminRepository.purgeTombstones]). Clients use this
     * list to physically forget tombstones ([app.skerry.shared.vault.Vault.compact]) and stop
     * pushing them, so a re-push can't resurrect them on the server after a purge.
     */
    suspend fun compactedTombstoneIds(accountId: String): List<String> = dbTransaction(db) {
        Records.selectAll()
            .where { propagatedTombstones(accountId, tombstoneWatermark(accountId)) }
            .map { it[Records.recordId] }
    }

    /** Delta: records with `serverSeq > since`, ordered by ascending cursor. */
    suspend fun delta(accountId: String, since: Long): List<StoredRecord> = dbTransaction(db) {
        Records.selectAll()
            .where { (Records.accountId eq accountId) and (Records.serverSeq greater since) }
            .orderBy(Records.serverSeq to SortOrder.ASC)
            .map { it.toStoredRecord() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toStoredRecord() = StoredRecord(
        id = this[Records.recordId],
        type = this[Records.type],
        version = this[Records.version],
        updatedAt = this[Records.updatedAt],
        deviceId = this[Records.deviceId],
        deleted = this[Records.deleted],
        blob = this[Records.blob].bytes,
        serverSeq = this[Records.serverSeq],
    )
}
