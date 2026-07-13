package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Encrypted team records — the same LWW core as [RecordRepository], but team-scoped: the delta
 * cursor is [Teams.teamSeq]. Tombstones aren't watermark-compacted (team membership is unstable);
 * [purgeTombstones] cleans them up by `updatedAt` age (ISO-8601 UTC, comparable lexicographically).
 * Clients apply a redelivered tombstone idempotently.
 */
class TeamRecordRepository(private val db: Database, private val lockTeamRow: Boolean = false) {

    /** Batch upsert with LWW by (`version`, `deviceId`) — same semantics as [RecordRepository.upsert]. */
    suspend fun upsert(teamId: String, incoming: List<IncomingRecord>): UpsertResult = newSuspendedTransaction(Dispatchers.IO, db) {
        val teamQuery = Teams.selectAll().where { Teams.id eq teamId }
        val seqBefore = (if (lockTeamRow) teamQuery.forUpdate() else teamQuery).single()[Teams.teamSeq]
        var seq = seqBefore

        val result = incoming.map { rec ->
            val existing = TeamRecords.selectAll()
                .where { (TeamRecords.teamId eq teamId) and (TeamRecords.recordId eq rec.id) }
                .singleOrNull()

            val wins = existing == null ||
                rec.version > existing[TeamRecords.version] ||
                (rec.version == existing[TeamRecords.version] && rec.deviceId > existing[TeamRecords.deviceId])

            if (wins) {
                seq += 1
                val newSeq = seq
                if (existing == null) {
                    TeamRecords.insert {
                        it[TeamRecords.teamId] = teamId
                        it[recordId] = rec.id
                        it[type] = rec.type
                        it[version] = rec.version
                        it[updatedAt] = rec.updatedAt
                        it[deviceId] = rec.deviceId
                        it[deleted] = rec.deleted
                        it[blob] = ExposedBlob(rec.blob)
                        it[teamSeq] = newSeq
                    }
                } else {
                    TeamRecords.update({ (TeamRecords.teamId eq teamId) and (TeamRecords.recordId eq rec.id) }) {
                        it[type] = rec.type
                        it[version] = rec.version
                        it[updatedAt] = rec.updatedAt
                        it[deviceId] = rec.deviceId
                        it[deleted] = rec.deleted
                        it[blob] = ExposedBlob(rec.blob)
                        it[teamSeq] = newSeq
                    }
                }
                StoredRecord(rec.id, rec.type, rec.version, rec.updatedAt, rec.deviceId, rec.deleted, rec.blob, newSeq)
            } else {
                existing.toStoredRecord()
            }
        }

        val changed = seq != seqBefore
        if (changed) {
            Teams.update({ Teams.id eq teamId }) { it[teamSeq] = seq }
        }
        UpsertResult(result, seq, changed)
    }

    /** Team delta: records with `teamSeq > since`, ordered by ascending cursor. */
    suspend fun delta(teamId: String, since: Long): List<StoredRecord> = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamRecords.selectAll()
            .where { (TeamRecords.teamId eq teamId) and (TeamRecords.teamSeq greater since) }
            .orderBy(TeamRecords.teamSeq to SortOrder.ASC)
            .map { it.toStoredRecord() }
    }

    /** Deletes tombstones older than [beforeIso] (ISO-8601 UTC) across all teams. Returns row count. */
    suspend fun purgeTombstones(beforeIso: String): Int = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamRecords.deleteWhere { (deleted eq true) and (updatedAt less beforeIso) }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toStoredRecord() = StoredRecord(
        id = this[TeamRecords.recordId],
        type = this[TeamRecords.type],
        version = this[TeamRecords.version],
        updatedAt = this[TeamRecords.updatedAt],
        deviceId = this[TeamRecords.deviceId],
        deleted = this[TeamRecords.deleted],
        blob = this[TeamRecords.blob].bytes,
        serverSeq = this[TeamRecords.teamSeq],
    )
}
