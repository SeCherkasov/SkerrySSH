package app.skerry.server.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** A page of one share space's delta plus the cursor to resume from (see [TeamRecordRepository.delta]). */
data class TeamDeltaPage(val records: List<StoredRecord>, val cursor: Long)

/**
 * Encrypted team records — the same LWW core as [RecordRepository], but scoped to one share space
 * of a team: the team itself (`scopeId` empty) or one of its scopes. The delta cursor is
 * [Teams.teamSeq], shared by every space of the team; a per-space cursor is just a watermark on it.
 * Tombstones aren't watermark-compacted (team membership is unstable); [purgeTombstones] cleans
 * them up by `updatedAt` age (ISO-8601 UTC, comparable lexicographically). Clients apply a
 * redelivered tombstone idempotently.
 */
class TeamRecordRepository(private val db: Database, private val lockTeamRow: Boolean = false) {

    /** Batch upsert with LWW by (`version`, `deviceId`) — same semantics as [RecordRepository.upsert]. */
    suspend fun upsert(teamId: String, scopeId: String, incoming: List<IncomingRecord>): UpsertResult = dbTransaction(db) {
        val teamQuery = Teams.selectAll().where { Teams.id eq teamId }
        val seqBefore = (if (lockTeamRow) teamQuery.forUpdate() else teamQuery).single()[Teams.teamSeq]
        var seq = seqBefore

        val result = incoming.mapNotNull { rec ->
            val existing = TeamRecords.selectAll()
                .where { (TeamRecords.teamId eq teamId) and (TeamRecords.recordId eq rec.id) }
                .singleOrNull()

            // A record belongs to exactly one space. A push from another one is dropped, not applied:
            // it would replace the ciphertext with one encrypted under a key this space's members
            // don't hold (a member with a stale local copy re-pushes it on every cycle), leaving the
            // record unreadable for everyone. The stored space stays authoritative.
            if (existing != null && existing[TeamRecords.scopeId] != scopeId) return@mapNotNull null

            val wins = existing == null ||
                rec.version > existing[TeamRecords.version] ||
                (rec.version == existing[TeamRecords.version] && rec.deviceId > existing[TeamRecords.deviceId])

            if (wins) {
                seq += 1
                val newSeq = seq
                if (existing == null) {
                    TeamRecords.insert {
                        it[TeamRecords.teamId] = teamId
                        it[TeamRecords.scopeId] = scopeId
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

    /**
     * One space's delta: its records in `since < teamSeq <= cursor`, ascending, where the cursor is
     * the team's [Teams.teamSeq]. Returning the counter rather than the last delivered row is what
     * keeps a scope from re-scanning records of other spaces on every pull.
     *
     * **The counter is read first and the record query is bounded by it** — the order is the whole
     * correctness argument, and a same-transaction read is not enough to replace it: PostgreSQL runs
     * READ COMMITTED by default, so a second statement sees a newer snapshot than the first. Reading
     * the records first would let a push commit in between and be reported as covered by a cursor that
     * never delivered it — lost for that client forever. With this order a push that lands in between
     * gets a `teamSeq` above the bound, is excluded, and arrives on the next pull. The reverse skew is
     * harmless: a record already committed at the time of the counter read is visible to the later
     * query too, because a record row and its team's counter are updated in one transaction.
     */
    suspend fun delta(teamId: String, scopeId: String, since: Long): TeamDeltaPage = dbTransaction(db) {
        val cursor = Teams.selectAll().where { Teams.id eq teamId }.singleOrNull()?.get(Teams.teamSeq) ?: since
        val records = TeamRecords.selectAll()
            .where {
                (TeamRecords.teamId eq teamId) and (TeamRecords.scopeId eq scopeId) and
                    (TeamRecords.teamSeq greater since) and (TeamRecords.teamSeq lessEq cursor)
            }
            .orderBy(TeamRecords.teamSeq to SortOrder.ASC)
            .map { it.toStoredRecord() }
        TeamDeltaPage(records, cursor)
    }

    /** Deletes tombstones older than [beforeIso] (ISO-8601 UTC) across all teams. Returns row count. */
    suspend fun purgeTombstones(beforeIso: String): Int = dbTransaction(db) {
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
