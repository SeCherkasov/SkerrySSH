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
 * What a winning write did to a record, as the team audit log reports it. The distinction is drawn
 * from what the space could see before, not from the row's existence: a write over a tombstone puts
 * the record back in front of the team, so it counts as [SHARED] again rather than an edit.
 */
enum class TeamRecordChange { SHARED, CHANGED, REMOVED }

/** Which share space of a team holds a record, and its (plaintext) record type. */
data class TeamRecordLocation(val scopeId: String, val type: String)

/** One record a push actually applied, with what it did. Records whose write lost LWW are absent. */
data class AppliedTeamRecord(val record: StoredRecord, val change: TeamRecordChange)

/**
 * Outcome of a team push: every pushed record as it now stands ([records], including the ones whose
 * write lost), the space cursor, and [applied] — what this push actually changed. A client pushes
 * all of its local records on every sync cycle, so [applied], not [records], is what the audit log
 * is built from.
 */
data class TeamUpsertResult(
    val records: List<StoredRecord>,
    val cursor: Long,
    val applied: List<AppliedTeamRecord>,
) {
    /** Whether anything moved at all — equivalently, whether the team's `teamSeq` advanced. */
    val changed: Boolean get() = applied.isNotEmpty()
}

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
    suspend fun upsert(teamId: String, scopeId: String, incoming: List<IncomingRecord>): TeamUpsertResult = dbTransaction(db) {
        val teamQuery = Teams.selectAll().where { Teams.id eq teamId }
        val seqBefore = (if (lockTeamRow) teamQuery.forUpdate() else teamQuery).single()[Teams.teamSeq]
        var seq = seqBefore
        val applied = mutableListOf<AppliedTeamRecord>()

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
                val change = when {
                    rec.deleted -> TeamRecordChange.REMOVED
                    // Nothing was visible here before: a new row, or one holding a tombstone.
                    existing == null || existing[TeamRecords.deleted] -> TeamRecordChange.SHARED
                    else -> TeamRecordChange.CHANGED
                }
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
                    .also { applied += AppliedTeamRecord(it, change) }
            } else {
                existing.toStoredRecord()
            }
        }

        if (applied.isNotEmpty()) {
            Teams.update({ Teams.id eq teamId }) { it[teamSeq] = seq }
        }
        TeamUpsertResult(result, seq, applied)
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

    /**
     * Where a record of the team lives and what it is, without its ciphertext — for validating a
     * report about it (see the session-event route). Null if the team never held such a record.
     * A tombstoned record still answers: a session on a host that was just unshared is real.
     */
    suspend fun locate(teamId: String, recordId: String): TeamRecordLocation? = dbTransaction(db) {
        TeamRecords.selectAll()
            .where { (TeamRecords.teamId eq teamId) and (TeamRecords.recordId eq recordId) }
            .singleOrNull()
            ?.let { TeamRecordLocation(it[TeamRecords.scopeId], it[TeamRecords.type]) }
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
