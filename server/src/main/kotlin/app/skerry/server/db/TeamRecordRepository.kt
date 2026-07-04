package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * Зашифрованные записи команд — то же LWW-ядро, что [RecordRepository], но в team-scope:
 * курсор дельты — [Teams.teamSeq]. Тромбстоуны watermark'ом не компактятся (состав команды
 * нестабилен) — их чистит [purgeTombstones] по возрасту `updatedAt` (ISO-8601 UTC сравнима
 * лексикографически); клиент повторную доставку тромбстоуна применяет идемпотентно.
 */
class TeamRecordRepository(private val db: Database, private val lockTeamRow: Boolean = false) {

    /** Batch upsert с LWW по (`version`, `deviceId`) — семантика 1:1 с [RecordRepository.upsert]. */
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

    /** Дельта команды: записи с `teamSeq > since`, по возрастанию курсора. */
    suspend fun delta(teamId: String, since: Long): List<StoredRecord> = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamRecords.selectAll()
            .where { (TeamRecords.teamId eq teamId) and (TeamRecords.teamSeq greater since) }
            .orderBy(TeamRecords.teamSeq to SortOrder.ASC)
            .map { it.toStoredRecord() }
    }

    /** Удаляет тромбстоуны старше [beforeIso] (ISO-8601 UTC) во всех командах. Возвращает число строк. */
    suspend fun purgeTombstones(beforeIso: String): Int = newSuspendedTransaction(Dispatchers.IO, db) {
        TeamRecords.deleteWhere { (deleted eq true) and (updatedAt less beforeIso) }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toStoredRecord() = StoredRecord(
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
