package app.skerry.server.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Server sees bounded ciphertext and routing metadata only. Every mutation is transactional. */
class TeamRecordingRepository(
    private val db: Database,
    private val lockRows: Boolean = false,
    private val maxScopeBytes: Long = 0,
    private val maxAccountBytes: Long = 0,
    private val activity: ActivityRepository = ActivityRepository(db),
) {
    suspend fun policy(teamId: String, scopeId: String): StoredRecordingPolicy? = dbTransaction(db) {
        TeamRecordingPolicies.selectAll().where {
            (TeamRecordingPolicies.teamId eq teamId) and (TeamRecordingPolicies.scopeId eq scopeId)
        }.singleOrNull()?.let {
            StoredRecordingPolicy(it[TeamRecordingPolicies.revision], it[TeamRecordingPolicies.keyEpoch],
                it[TeamRecordingPolicies.retentionDays], it[TeamRecordingPolicies.ciphertext].bytes,
                it[TeamRecordingPolicies.signature].bytes)
        }
    }

    suspend fun putPolicy(
        teamId: String, scopeId: String, actorId: String, policy: StoredRecordingPolicy, now: Long,
    ): RecordingPolicyResult = dbTransaction(db) {
        val team = teamRow(teamId) ?: return@dbTransaction RecordingPolicyResult.DENIED
        if (team[Teams.ownerAccountId] != actorId || !canAccess(teamId, scopeId, actorId)) {
            return@dbTransaction RecordingPolicyResult.DENIED
        }
        val epoch = spaceEpoch(team, scopeId) ?: return@dbTransaction RecordingPolicyResult.DENIED
        if (!validRecordingPolicy(policy, epoch) || !ownerSignedPolicy(teamId, scopeId, actorId, policy)) {
            return@dbTransaction RecordingPolicyResult.CONFLICT
        }
        val where = (TeamRecordingPolicies.teamId eq teamId) and (TeamRecordingPolicies.scopeId eq scopeId)
        val old = TeamRecordingPolicies.selectAll().where { where }.singleOrNull()
        val expectedRevision = (old?.get(TeamRecordingPolicies.revision) ?: 0L) + 1
        if (policy.revision != expectedRevision) return@dbTransaction RecordingPolicyResult.CONFLICT
        if (old == null) TeamRecordingPolicies.insert {
            it[TeamRecordingPolicies.teamId] = teamId
            it[TeamRecordingPolicies.scopeId] = scopeId
            it[revision] = policy.revision
            it[keyEpoch] = policy.keyEpoch
            it[retentionDays] = policy.retentionDays
            it[ciphertext] = ExposedBlob(policy.ciphertext)
            it[signature] = ExposedBlob(policy.signature)
            it[updatedAt] = now
        } else TeamRecordingPolicies.update({ where }) {
            it[revision] = policy.revision
            it[keyEpoch] = policy.keyEpoch
            it[retentionDays] = policy.retentionDays
            it[ciphertext] = ExposedBlob(policy.ciphertext)
            it[signature] = ExposedBlob(policy.signature)
            it[updatedAt] = now
        }
        RecordingPolicyResult.UPDATED
    }

    suspend fun reserve(spec: RecordingReservation, now: Long): RecordingReserveResult = dbTransaction(db) {
        val team = teamRow(spec.teamId) ?: return@dbTransaction RecordingReserveResult.DENIED
        if (!canAccess(spec.teamId, spec.scopeId, spec.actorId)) return@dbTransaction RecordingReserveResult.DENIED
        if (!validRecordingReservation(spec)) return@dbTransaction RecordingReserveResult.TOO_LARGE
        val existing = row(spec.teamId, spec.recordingId)
        if (existing?.get(TeamRecordings.chunkCount) == 0) {
            return@dbTransaction if (existing[TeamRecordings.actorId] == spec.actorId) RecordingReserveResult.RETIRED
                else RecordingReserveResult.CONFLICT
        }
        val policy = TeamRecordingPolicies.selectAll().where {
            (TeamRecordingPolicies.teamId eq spec.teamId) and (TeamRecordingPolicies.scopeId eq spec.scopeId)
        }.singleOrNull() ?: return@dbTransaction RecordingReserveResult.NO_POLICY
        if (spaceEpoch(team, spec.scopeId) != spec.wrapEpoch ||
            policy[TeamRecordingPolicies.keyEpoch] != spec.wrapEpoch
        ) return@dbTransaction RecordingReserveResult.CONFLICT
        if (existing != null) return@dbTransaction retryReservation(existing, spec, now)
        if (policy[TeamRecordingPolicies.retentionDays] != spec.retentionDays) return@dbTransaction RecordingReserveResult.CONFLICT
        if (!hostExists(spec)) return@dbTransaction RecordingReserveResult.NO_HOST
        val bytes = recordingReservedBytes(spec)
        lockQuotaOwner(team)
        insertReservation(spec, bytes, now)
        enforceReservationQuota(spec, bytes, team[Teams.ownerAccountId])
        RecordingReserveResult.CREATED
    }

    suspend fun putChunk(teamId: String, recordingId: String, actorId: String, index: Int, bytes: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): RecordingChunkResult =
        dbTransaction(db) {
            teamRow(teamId) ?: return@dbTransaction RecordingChunkResult.NOT_FOUND
            val record = row(teamId, recordingId) ?: return@dbTransaction RecordingChunkResult.NOT_FOUND
            if (record[TeamRecordings.expiresAt] <= now) return@dbTransaction RecordingChunkResult.NOT_FOUND
            if (record[TeamRecordings.actorId] != actorId || !canAccess(teamId, record[TeamRecordings.scopeId], actorId)) {
                return@dbTransaction RecordingChunkResult.DENIED
            }
            if (record[TeamRecordings.ready]) return@dbTransaction RecordingChunkResult.CONFLICT
            val where = (TeamRecordingChunks.teamId eq teamId) and
                (TeamRecordingChunks.recordingId eq recordingId) and (TeamRecordingChunks.index eq index)
            val slot = TeamRecordingChunks.selectAll().where { where }.singleOrNull()
                ?: return@dbTransaction RecordingChunkResult.NOT_FOUND
            if (bytes.size != slot[TeamRecordingChunks.expectedLength] || sha256Hex(bytes) != slot[TeamRecordingChunks.expectedSha256]) {
                return@dbTransaction RecordingChunkResult.CONFLICT
            }
            val current = slot[TeamRecordingChunks.ciphertext]?.bytes
            if (current != null) return@dbTransaction if (current.contentEquals(bytes)) RecordingChunkResult.SAME
                else RecordingChunkResult.CONFLICT
            TeamRecordingChunks.update({ where }) { it[TeamRecordingChunks.ciphertext] = ExposedBlob(bytes) }
            RecordingChunkResult.CREATED
        }

    /** READY and its activity link are committed together, exactly once. */
    suspend fun complete(teamId: String, recordingId: String, actorId: String, now: Long): Boolean = dbTransaction(db) {
        val team = teamRow(teamId) ?: return@dbTransaction false
        val record = row(teamId, recordingId) ?: return@dbTransaction false
        if (record[TeamRecordings.expiresAt] <= now) return@dbTransaction false
        if (record[TeamRecordings.actorId] != actorId || !canAccess(teamId, record[TeamRecordings.scopeId], actorId)) {
            return@dbTransaction false
        }
        if (record[TeamRecordings.ready]) return@dbTransaction true
        val currentEpoch = TeamRecordingPolicies.selectAll().where {
            (TeamRecordingPolicies.teamId eq teamId) and
                (TeamRecordingPolicies.scopeId eq record[TeamRecordings.scopeId])
        }.singleOrNull()?.get(TeamRecordingPolicies.keyEpoch) ?: return@dbTransaction false
        if (record[TeamRecordings.wrapEpoch] != currentEpoch ||
            spaceEpoch(team, record[TeamRecordings.scopeId]) != currentEpoch
        ) return@dbTransaction false
        if (!allChunksValid(record)) return@dbTransaction false
        val expiry = now + record[TeamRecordings.retentionDays].toLong() * DAY_MS
        TeamRecordings.update({ (TeamRecordings.teamId eq teamId) and (TeamRecordings.recordingId eq recordingId) }) {
            it[TeamRecordings.ready] = true
            it[TeamRecordings.expiresAt] = expiry
        }
        activity.appendInCurrentTransaction(ActivityEvent(
            accountId = actorId, event = "team.session_record", detail = "", teamId = teamId,
            recordId = record[TeamRecordings.hostId], recordType = "HOST", scopeId = record[TeamRecordings.scopeId],
            durationSec = record[TeamRecordings.durationSec], recordingId = recordingId,
        ), now)
        true
    }

    suspend fun ready(teamId: String, recordingId: String, readerId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): StoredRecording? = dbTransaction(db) {
        val record = row(teamId, recordingId) ?: return@dbTransaction null
        if (!isReadable(record, readerId, now)) {
            return@dbTransaction null
        }
        record.toStoredRecording()
    }

    suspend fun listReady(teamId: String, scopeId: String, readerId: String? = null, limit: Int = 50, offset: Long = 0,
        now: Long = System.currentTimeMillis(),
    ):
        List<StoredRecording> = dbTransaction(db) {
        if (readerId != null && !canAccess(teamId, scopeId, readerId)) return@dbTransaction emptyList()
        TeamRecordings.selectAll().where {
            (TeamRecordings.teamId eq teamId) and (TeamRecordings.scopeId eq scopeId) and (TeamRecordings.ready eq true) and
                (TeamRecordings.expiresAt greater now)
        }.orderBy(TeamRecordings.createdAt to SortOrder.DESC).limit(limit).offset(offset).map { it.toStoredRecording() }
    }

    suspend fun chunk(teamId: String, recordingId: String, index: Int, readerId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ByteArray? = dbTransaction(db) {
        val record = row(teamId, recordingId) ?: return@dbTransaction null
        if (!isReadable(record, readerId, now)) {
            return@dbTransaction null
        }
        TeamRecordingChunks.selectAll().where {
            (TeamRecordingChunks.teamId eq teamId) and (TeamRecordingChunks.recordingId eq recordingId) and
                (TeamRecordingChunks.index eq index)
        }.singleOrNull()?.get(TeamRecordingChunks.ciphertext)?.bytes
    }

    suspend fun delete(teamId: String, recordingId: String, ownerId: String): Boolean = dbTransaction(db) {
        val team = teamRow(teamId) ?: return@dbTransaction false
        if (team[Teams.ownerAccountId] != ownerId || row(teamId, recordingId) == null) return@dbTransaction false
        retireRecording(teamId, recordingId)
        true
    }

    /** All ids share one owner check and transaction; absent ids do not fail retries. */
    suspend fun deleteMany(teamId: String, recordingIds: List<String>, ownerId: String): Int? = dbTransaction(db) {
        val team = teamRow(teamId) ?: return@dbTransaction null
        if (team[Teams.ownerAccountId] != ownerId) return@dbTransaction null
        recordingIds.distinct().count { id ->
            if (row(teamId, id) == null) false else {
                retireRecording(teamId, id)
                true
            }
        }
    }

    /** Stage a wrap for the next space epoch before the key rotation commits. */
    suspend fun stageWrap(
        teamId: String, recordingId: String, actorId: String, nextEpoch: Long, wrappedKey: ByteArray,
    ): Boolean = dbTransaction(db) {
        val team = teamRow(teamId) ?: return@dbTransaction false
        val row = row(teamId, recordingId) ?: return@dbTransaction false
        if (row[TeamRecordings.chunkCount] == 0) return@dbTransaction false
        val member = TeamMembers.selectAll().where {
            (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq actorId)
        }.singleOrNull() ?: return@dbTransaction false
        if (!isManager(member) || !canAccess(teamId, row[TeamRecordings.scopeId], actorId)) return@dbTransaction false
        if (wrappedKey.size !in 40..256) return@dbTransaction false
        val currentEpoch = if (row[TeamRecordings.scopeId].isEmpty()) team[Teams.keyEpoch]
            else TeamScopes.selectAll().where {
                (TeamScopes.teamId eq teamId) and (TeamScopes.scopeId eq row[TeamRecordings.scopeId])
            }.singleOrNull()?.get(TeamScopes.keyEpoch) ?: return@dbTransaction false
        if (nextEpoch != currentEpoch + 1) return@dbTransaction false
        val where = (TeamRecordings.teamId eq teamId) and (TeamRecordings.recordingId eq recordingId)
        TeamRecordings.update({ where }) {
            if (row[TeamRecordings.stagedWrapEpoch] == currentEpoch) {
                it[TeamRecordings.wrapEpoch] = currentEpoch
                it[TeamRecordings.wrappedKey] = row[TeamRecordings.stagedWrappedKey]!!
            }
            it[TeamRecordings.stagedWrapEpoch] = nextEpoch
            it[TeamRecordings.stagedWrappedKey] = ExposedBlob(wrappedKey)
        }
        true
    }

    suspend fun cleanupExpired(now: Long): Int = dbTransaction(db) {
        val expired = TeamRecordings.select(TeamRecordings.teamId, TeamRecordings.recordingId).where {
            (TeamRecordings.expiresAt lessEq now) and (TeamRecordings.chunkCount greater 0)
        }
            .map { it[TeamRecordings.teamId] to it[TeamRecordings.recordingId] }
        val deleted = expired.sortedWith(compareBy({ it.first }, { it.second })).count { (team, id) ->
            teamRow(team)
            // Re-check after taking the lock: completion may have extended an incomplete TTL.
            val record = row(team, id)
            if (record == null || record[TeamRecordings.chunkCount] == 0) return@count false
            if (record[TeamRecordings.expiresAt] > now) return@count false
            if (record[TeamRecordings.ready]) retireRecording(team, id) else deleteIncompleteRecording(team, id)
            true
        }
        deleted
    }

    private fun ownerSignedPolicy(teamId: String, scopeId: String, actorId: String, policy: StoredRecordingPolicy): Boolean {
        val signing = AccountKeys.selectAll().where { AccountKeys.accountId eq actorId }
            .singleOrNull()?.get(AccountKeys.signPublicKey)?.bytes ?: return false
        return signing.size == 32 && verifyPolicySignature(teamId, scopeId, policy, signing)
    }

    private fun retryReservation(existing: ResultRow, spec: RecordingReservation, now: Long): RecordingReserveResult {
        if (existing[TeamRecordings.expiresAt] <= now || !sameReservation(existing, spec)) return RecordingReserveResult.CONFLICT
        if (chunkSpecs(spec.teamId, spec.recordingId) != spec.chunks) return RecordingReserveResult.CONFLICT
        val sameWrap = existing[TeamRecordings.wrapEpoch] == spec.wrapEpoch &&
            existing[TeamRecordings.wrappedKey].bytes.contentEquals(spec.wrappedKey)
        if (!sameWrap) {
            if (existing[TeamRecordings.ready] || spec.wrapEpoch <= existing[TeamRecordings.wrapEpoch]) {
                return RecordingReserveResult.CONFLICT
            }
            TeamRecordings.update({ (TeamRecordings.teamId eq spec.teamId) and
                (TeamRecordings.recordingId eq spec.recordingId) }) {
                it[TeamRecordings.wrapEpoch] = spec.wrapEpoch
                it[TeamRecordings.wrappedKey] = ExposedBlob(spec.wrappedKey)
            }
        }
        return RecordingReserveResult.SAME
    }

    private fun sameReservation(existing: ResultRow, spec: RecordingReservation): Boolean {
        val stored = listOf(existing[TeamRecordings.actorId], existing[TeamRecordings.scopeId],
            existing[TeamRecordings.hostId], existing[TeamRecordings.keyEpoch], existing[TeamRecordings.durationSec],
            existing[TeamRecordings.reservedBytes])
        val requested = listOf(spec.actorId, spec.scopeId, spec.hostId, spec.keyEpoch, spec.durationSec,
            recordingReservedBytes(spec))
        return stored == requested && existing[TeamRecordings.manifest].bytes.contentEquals(spec.manifest)
    }

    private fun hostExists(spec: RecordingReservation): Boolean {
        val host = TeamRecords.selectAll().where {
            (TeamRecords.teamId eq spec.teamId) and (TeamRecords.recordId eq spec.hostId) and
                (TeamRecords.scopeId eq spec.scopeId)
        }.singleOrNull() ?: return false
        return host[TeamRecords.type] == "HOST" && !host[TeamRecords.deleted]
    }

    private fun insertReservation(spec: RecordingReservation, bytes: Long, now: Long) {
        TeamRecordings.insert {
            it[TeamRecordings.teamId] = spec.teamId
            it[TeamRecordings.scopeId] = spec.scopeId
            it[TeamRecordings.recordingId] = spec.recordingId
            it[TeamRecordings.hostId] = spec.hostId
            it[TeamRecordings.actorId] = spec.actorId
            it[TeamRecordings.keyEpoch] = spec.keyEpoch
            it[TeamRecordings.wrappedKey] = ExposedBlob(spec.wrappedKey)
            it[TeamRecordings.wrapEpoch] = spec.wrapEpoch
            it[TeamRecordings.stagedWrapEpoch] = null
            it[TeamRecordings.stagedWrappedKey] = null
            it[TeamRecordings.manifest] = ExposedBlob(spec.manifest)
            it[TeamRecordings.chunkCount] = spec.chunks.size
            it[TeamRecordings.reservedBytes] = bytes
            it[TeamRecordings.durationSec] = spec.durationSec
            it[TeamRecordings.retentionDays] = spec.retentionDays
            it[TeamRecordings.createdAt] = now
            it[TeamRecordings.expiresAt] = now + INCOMPLETE_TTL_MS
            it[TeamRecordings.ready] = false
        }
        spec.chunks.forEach { chunk -> TeamRecordingChunks.insert {
            it[TeamRecordingChunks.teamId] = spec.teamId
            it[TeamRecordingChunks.recordingId] = spec.recordingId
            it[TeamRecordingChunks.index] = chunk.index
            it[TeamRecordingChunks.expectedLength] = chunk.length
            it[TeamRecordingChunks.expectedSha256] = chunk.sha256
            it[TeamRecordingChunks.ciphertext] = null
        } }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.lockQuotaOwner(team: ResultRow) {
        if (lockRows && maxAccountBytes > 0) {
            Accounts.selectAll().where { Accounts.id eq team[Teams.ownerAccountId] }.forUpdate().single()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.enforceReservationQuota(
        spec: RecordingReservation, bytes: Long, ownerId: String,
    ) {
        // Both sums run in SQL after the reserved row exists. A failed check rolls the row back.
        enforceQuota(StorageQuota.SPACE, maxScopeBytes, bytes,
            "SELECT (SELECT COALESCE(SUM(LENGTH(blob)), 0) FROM team_records WHERE team_id = ? AND scope_id = ?) + " +
                "(SELECT COALESCE(SUM(reserved_bytes), 0) FROM team_recordings WHERE team_id = ? AND scope_id = ?)",
            listOf(
                VarCharColumnType(64) to spec.teamId, VarCharColumnType(64) to spec.scopeId,
                VarCharColumnType(64) to spec.teamId, VarCharColumnType(64) to spec.scopeId,
            ))
        enforceAccountQuota(maxAccountBytes, bytes, ownerId)
    }

    /** Metadata-only scan: the JDBC driver never buffers the recording's ciphertext set. */
    private fun chunkSpecs(teamId: String, recordingId: String): List<RecordingChunkSpec> =
        TeamRecordingChunks.select(TeamRecordingChunks.index, TeamRecordingChunks.expectedLength,
            TeamRecordingChunks.expectedSha256).where {
            (TeamRecordingChunks.teamId eq teamId) and (TeamRecordingChunks.recordingId eq recordingId)
        }.orderBy(TeamRecordingChunks.index to SortOrder.ASC).map {
            RecordingChunkSpec(it[TeamRecordingChunks.index], it[TeamRecordingChunks.expectedLength],
                it[TeamRecordingChunks.expectedSha256])
        }

    private fun allChunksValid(record: ResultRow): Boolean {
        val teamId = record[TeamRecordings.teamId]
        val recordingId = record[TeamRecordings.recordingId]
        val specs = chunkSpecs(teamId, recordingId)
        if (specs.size != record[TeamRecordings.chunkCount]) return false
        return specs.all { validStoredChunk(teamId, recordingId, it) }
    }

    private fun validStoredChunk(teamId: String, recordingId: String, spec: RecordingChunkSpec): Boolean {
        // Fetch a single <=2 MiB blob, validate it, then release it before reading the next one.
        val bytes = TeamRecordingChunks.select(TeamRecordingChunks.ciphertext).where {
            (TeamRecordingChunks.teamId eq teamId) and (TeamRecordingChunks.recordingId eq recordingId) and
                (TeamRecordingChunks.index eq spec.index)
        }.singleOrNull()?.get(TeamRecordingChunks.ciphertext)?.bytes ?: return false
        return bytes.size == spec.length && sha256Hex(bytes) == spec.sha256
    }

    private fun isReadable(record: ResultRow, readerId: String?, now: Long): Boolean {
        if (record[TeamRecordings.expiresAt] <= now || !record[TeamRecordings.ready]) return false
        return readerId == null || canAccess(record[TeamRecordings.teamId], record[TeamRecordings.scopeId], readerId)
    }

    private fun isManager(member: ResultRow): Boolean = member[TeamMembers.status] == TeamMemberStatus.ACTIVE &&
        member[TeamMembers.role] in listOf(TeamRoles.OWNER, TeamRoles.ADMIN)

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.teamRow(teamId: String): ResultRow? {
        val query = Teams.selectAll().where { Teams.id eq teamId }
        return (if (lockRows) query.forUpdate() else query).singleOrNull()
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.spaceEpoch(team: ResultRow, scopeId: String): Long? {
        if (scopeId.isEmpty()) return team[Teams.keyEpoch]
        val query = TeamScopes.selectAll().where {
            (TeamScopes.teamId eq team[Teams.id]) and (TeamScopes.scopeId eq scopeId)
        }
        return (if (lockRows) query.forUpdate() else query).singleOrNull()?.get(TeamScopes.keyEpoch)
    }

    private fun canAccess(teamId: String, scopeId: String, accountId: String): Boolean {
        val member = TeamMembers.selectAll().where {
            (TeamMembers.teamId eq teamId) and (TeamMembers.accountId eq accountId)
        }.singleOrNull() ?: return false
        if (member[TeamMembers.status] != TeamMemberStatus.ACTIVE) return false
        return scopeId.isEmpty() || TeamScopeGrants.selectAll().where {
            (TeamScopeGrants.teamId eq teamId) and (TeamScopeGrants.scopeId eq scopeId) and
                (TeamScopeGrants.accountId eq accountId)
        }.any()
    }

    private fun row(teamId: String, id: String): ResultRow? = TeamRecordings.selectAll().where {
        (TeamRecordings.teamId eq teamId) and (TeamRecordings.recordingId eq id)
    }.singleOrNull()

    private fun retireRecording(teamId: String, id: String) {
        TeamRecordingChunks.deleteWhere { (TeamRecordingChunks.teamId eq teamId) and (recordingId eq id) }
        TeamRecordings.update({ (TeamRecordings.teamId eq teamId) and (TeamRecordings.recordingId eq id) }) {
            it[chunkCount] = 0
            it[ready] = false
            it[expiresAt] = 0
            it[reservedBytes] = RECEIPT_BYTES
            it[manifest] = ExposedBlob(ByteArray(0))
            it[wrappedKey] = ExposedBlob(ByteArray(0))
            it[stagedWrapEpoch] = null
            it[stagedWrappedKey] = null
        }
    }

    private fun deleteIncompleteRecording(teamId: String, id: String) {
        TeamRecordingChunks.deleteWhere { (TeamRecordingChunks.teamId eq teamId) and (recordingId eq id) }
        TeamRecordings.deleteWhere { (TeamRecordings.teamId eq teamId) and (recordingId eq id) }
    }

    companion object {
        /** Charged for a terminal id/actor/scope receipt until its space is deleted. */
        const val RECEIPT_BYTES = 1024L
        const val MAX_CHUNK_BYTES = 2 * 1024 * 1024
        const val MAX_CHUNKS = 128
        const val MAX_RECORDING_BYTES = 256L * 1024 * 1024
        private const val DAY_MS = 86_400_000L
        private const val INCOMPLETE_TTL_MS = DAY_MS
    }
}

