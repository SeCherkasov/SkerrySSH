package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

class TeamRecordingSecurityTest {
    private val owner = "owner@example.com"
    private val editor = "editor@example.com"
    private val bytes = ByteArray(40) { it.toByte() }

    private suspend fun seed(db: Database, scope: String = ""): TeamRecordingRepository {
        seedAccount(db, owner)
        seedAccount(db, editor)
        val teams = TeamRepository(db)
        teams.create("team-1", owner, 1)
        teams.invite("team-1", editor, TeamRoles.EDITOR, byteArrayOf(1), owner, 2)
        teams.accept("team-1", editor)
        if (scope.isNotEmpty()) TeamScopeRepository(db).create("team-1", scope, owner, byteArrayOf(1), 3)
        TeamRecordRepository(db).upsert("team-1", scope, listOf(
            IncomingRecord("host-1", "HOST", 1, "2026-07-04T00:00:00Z", "dev-a", false, byteArrayOf(1)),
        ))
        val repo = TeamRecordingRepository(db)
        assertEquals(RecordingPolicyResult.UPDATED, repo.putPolicy("team-1", scope, owner,
            signedPolicyFixture(teams, owner, "team-1", scope), 5))
        return repo
    }

    private fun reservation(id: String = "rec-1", scope: String = "") = RecordingReservation(
        "team-1", scope, id, "host-1", owner, 0, ByteArray(40), ByteArray(40),
        listOf(RecordingChunkSpec(0, bytes.size, sha256Hex(bytes))), 20, 30,
    )




    @Test
    fun `team rekey blocks an unstaged ready recording including completion after final page`() = withTestDb { db ->
        val repo = seed(db)
        val now = System.currentTimeMillis()
        repo.reserve(reservation(), now)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = now + 1)
        repo.complete("team-1", "rec-1", owner, now + 2)
        val teams = TeamRepository(db)
        val envelopes = mapOf(owner to ByteArray(40), editor to ByteArray(40))
        assertEquals("RECORDING_WRAP_REQUIRED", teams.rekey("team-1", 1, envelopes).name)
        assertEquals(0L, teams.team("team-1")!!.keyEpoch)
        assertTrue(repo.stageWrap("team-1", "rec-1", owner, 1, ByteArray(40)))
        // A completion can arrive after the owner's final recording page was staged.
        repo.reserve(reservation("rec-2"), now + 3)
        repo.putChunk("team-1", "rec-2", owner, 0, bytes, now = now + 4)
        assertTrue(repo.complete("team-1", "rec-2", owner, now + 5))
        assertEquals("RECORDING_WRAP_REQUIRED", teams.rekey("team-1", 1, envelopes).name)
        assertEquals(0L, teams.team("team-1")!!.keyEpoch)
        assertTrue(repo.stageWrap("team-1", "rec-2", owner, 1, ByteArray(40)))
        assertEquals(RekeyOutcome.OK, teams.rekey("team-1", 1, envelopes))
        assertEquals(1L, teams.team("team-1")!!.keyEpoch)
    }

    @Test
    fun `scope rekey blocks an unstaged ready recording without advancing epoch`() = withTestDb { db ->
        val repo = seed(db, "prod")
        val now = System.currentTimeMillis()
        repo.reserve(reservation(scope = "prod"), now)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = now + 1)
        repo.complete("team-1", "rec-1", owner, now + 2)
        val scopes = TeamScopeRepository(db)
        val envelopes = mapOf(owner to ByteArray(40))
        assertEquals("RECORDING_WRAP_REQUIRED", scopes.rekey("team-1", "prod", 1, envelopes).name)
        assertEquals(0L, scopes.scopesFor("team-1", owner, false).single().keyEpoch)
        assertTrue(repo.stageWrap("team-1", "rec-1", owner, 1, ByteArray(40)))
        assertEquals(RekeyOutcome.OK, scopes.rekey("team-1", "prod", 1, envelopes))
        assertEquals(1L, scopes.scopesFor("team-1", owner, false).single().keyEpoch)
    }

    @Test
    fun `incomplete immutable upload can update wrap after rotation and policy retention change`() = withTestDb { db ->
        val repo = seed(db)
        val spec = reservation()
        repo.reserve(spec, 10)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        val teams = TeamRepository(db)
        assertEquals(RekeyOutcome.OK, teams.rekey("team-1", 1, mapOf(owner to ByteArray(40))))
        assertEquals(RecordingPolicyResult.UPDATED, repo.putPolicy("team-1", "", owner,
            signedPolicyFixture(teams, owner, "team-1", revision = 2, epoch = 1, retentionDays = 15), 12))
        val nextWrap = ByteArray(40) { 9 }
        assertEquals(RecordingReserveResult.SAME, repo.reserve(spec.copy(wrapEpoch = 1, wrappedKey = nextWrap), 13))
        assertTrue(repo.complete("team-1", "rec-1", owner, 14))
        val ready = assertNotNull(repo.ready("team-1", "rec-1", owner, now = 15))
        assertContentEquals(nextWrap, ready.wrappedKey)
        assertEquals(1L, ready.wrapEpoch)
        assertEquals(0L, ready.keyEpoch)
        assertEquals(14 + 30 * 86_400_000L, ready.expiresAt)
    }

    @Test
    fun `deleted completed id stays terminal after activity pruning`() = withTestDb { db ->
        val repo = seed(db)
        val spec = reservation()
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(spec, 10))
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        assertTrue(repo.complete("team-1", "rec-1", owner, 12))
        assertTrue(repo.stageWrap("team-1", "rec-1", owner, 1, ByteArray(40)))
        assertTrue(repo.delete("team-1", "rec-1", owner))
        // The audit feed cannot serve as the durable receipt: its rows are independently prunable.
        dbTransaction(db) { ActivityLog.deleteWhere { ActivityLog.teamId eq "team-1" } }
        assertEquals("RETIRED", repo.reserve(spec, 13).name)
        assertEquals(RecordingChunkResult.NOT_FOUND, repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 14))
        assertFalse(repo.complete("team-1", "rec-1", owner, 15))
        assertNull(repo.ready("team-1", "rec-1", owner, now = 15))
        assertNull(repo.chunk("team-1", "rec-1", 0, owner, now = 15))
        assertTrue(repo.listReady("team-1", "", owner, now = 15).isEmpty())
        val inventory = StatsRepository(db).inventory("jdbc:sqlite:test", now = 15)
        assertEquals(1024L, inventory.recordingStorageBytes)
        assertEquals(1024L, inventory.recordingReservedBytes)
        dbTransaction(db) {
            assertEquals(0L, TeamRecordingChunks.selectAll().count())
            val receipt = TeamRecordings.selectAll().single()
            assertEquals(1024L, receipt[TeamRecordings.reservedBytes])
            assertEquals(0, receipt[TeamRecordings.manifest].bytes.size)
            assertEquals(0, receipt[TeamRecordings.wrappedKey].bytes.size)
            assertNull(receipt[TeamRecordings.stagedWrappedKey])
            assertEquals(0L, ActivityLog.selectAll().where { ActivityLog.teamId eq "team-1" }.count())
        }
    }

    @Test
    fun `expired completed id stays terminal after cleanup`() = withTestDb { db ->
        val repo = seed(db)
        val spec = reservation()
        repo.reserve(spec, 10)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        repo.complete("team-1", "rec-1", owner, 12)
        val expiry = 12 + 30 * 86_400_000L
        assertEquals(1, repo.cleanupExpired(expiry))
        assertEquals("RETIRED", repo.reserve(spec, expiry + 1).name)
        assertEquals(0, repo.cleanupExpired(expiry + 2))
        assertFalse(repo.complete("team-1", "rec-1", owner, expiry + 3))
        dbTransaction(db) {
            assertEquals(1L, ActivityLog.selectAll().where { ActivityLog.recordingId eq "rec-1" }.count())
            assertEquals(0L, TeamRecordingChunks.selectAll().count())
        }
    }

    @Test
    fun `recording completion respects activity team retention`() = withTestDb { db ->
        val repo = seed(db)
        dbTransaction(db) {
            repeat(500) { index -> ActivityLog.insert {
                it[accountId] = owner
                it[event] = "team.session_start"
                it[detail] = ""
                it[teamId] = "team-1"
                it[createdAt] = index.toLong()
            } }
        }
        repo.reserve(reservation(), 1_000)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 1_001)
        assertTrue(repo.complete("team-1", "rec-1", owner, 1_002))
        assertTrue(repo.complete("team-1", "rec-1", owner, 1_003))
        dbTransaction(db) {
            assertEquals(500L, ActivityLog.selectAll().where { ActivityLog.teamId eq "team-1" }.count())
            assertEquals(1L, ActivityLog.selectAll().where { ActivityLog.recordingId eq "rec-1" }.count())
        }
    }

    @Test
    fun `completion checks every persisted chunk including later corrupted ciphertext`() = withTestDb { db ->
        val repo = seed(db)
        val chunks = List(3) { index -> ByteArray(40) { (it + index).toByte() } }
        val spec = reservation().copy(chunks = chunks.mapIndexed { index, data ->
            RecordingChunkSpec(index, data.size, sha256Hex(data))
        })
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(spec, 10))
        chunks.forEachIndexed { index, data ->
            assertEquals(RecordingChunkResult.CREATED, repo.putChunk("team-1", "rec-1", owner, index, data, now = 11))
        }
        dbTransaction(db) {
            TeamRecordingChunks.update({ (TeamRecordingChunks.teamId eq "team-1") and
                (TeamRecordingChunks.recordingId eq "rec-1") and (TeamRecordingChunks.index eq 2) }) {
                it[ciphertext] = ExposedBlob(ByteArray(40))
            }
        }
        assertFalse(repo.complete("team-1", "rec-1", owner, 12))
        assertNull(repo.ready("team-1", "rec-1", owner, now = 12))
        dbTransaction(db) {
            assertEquals(0L, ActivityLog.selectAll().where { ActivityLog.recordingId eq "rec-1" }.count())
            TeamRecordingChunks.update({ (TeamRecordingChunks.teamId eq "team-1") and
                (TeamRecordingChunks.recordingId eq "rec-1") and (TeamRecordingChunks.index eq 2) }) {
                it[ciphertext] = ExposedBlob(chunks[2])
            }
        }
        assertTrue(repo.complete("team-1", "rec-1", owner, 13))
    }

    @Test
    fun `scope revocation denies ciphertext and every upload mutation`() = withTestDb { db ->
        val repo = seed(db, "prod")
        val scopes = TeamScopeRepository(db)
        assertEquals(RecordingReserveResult.DENIED, repo.reserve(reservation(scope = "prod").copy(actorId = editor), 10))
        scopes.grant("team-1", "prod", editor, byteArrayOf(1), 6)
        val spec = reservation(scope = "prod").copy(actorId = editor)
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(spec, 10))
        assertEquals(RecordingChunkResult.DENIED, repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11))
        assertFalse(repo.complete("team-1", "rec-1", owner, 11))
        assertEquals(RecordingChunkResult.CREATED, repo.putChunk("team-1", "rec-1", editor, 0, bytes, now = 11))
        assertTrue(repo.complete("team-1", "rec-1", editor, 11))
        assertContentEquals(bytes, repo.chunk("team-1", "rec-1", 0, editor, now = 12))
        scopes.revoke("team-1", "prod", editor)
        assertNull(repo.ready("team-1", "rec-1", editor, now = 12))
        assertNull(repo.chunk("team-1", "rec-1", 0, editor, now = 12))
        assertTrue(repo.listReady("team-1", "prod", editor, now = 12).isEmpty())
        assertFalse(repo.complete("team-1", "rec-1", editor, 12))
        assertFalse(repo.delete("team-1", "rec-1", editor))
    }

    @Test
    fun `policy signature binds revision scope retention and owner`() = withTestDb { db ->
        val repo = seed(db)
        val policy = signedPolicyFixture(TeamRepository(db), owner, "team-1", revision = 2)
        assertEquals(RecordingPolicyResult.DENIED, repo.putPolicy("team-1", "", editor, policy, 10))
        assertEquals(RecordingPolicyResult.CONFLICT, repo.putPolicy("team-1", "", owner,
            policy.copy(retentionDays = 1), 10))
        assertEquals(RecordingPolicyResult.CONFLICT, repo.putPolicy("team-1", "", owner,
            policy.copy(signature = ByteArray(64)), 10))
        assertEquals(RecordingPolicyResult.UPDATED, repo.putPolicy("team-1", "", owner, policy, 10))
        assertEquals(RecordingPolicyResult.CONFLICT, repo.putPolicy("team-1", "", owner, policy, 11))
        assertEquals(2, repo.policy("team-1", "")!!.revision.toInt())
    }

    @Test
    fun `reservation immutable metadata cannot change on retries`() = withTestDb { db ->
        val repo = seed(db)
        val spec = reservation()
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(spec, 10))
        assertEquals(RecordingReserveResult.SAME, repo.reserve(spec, 11))
        assertEquals(RecordingReserveResult.CONFLICT, repo.reserve(spec.copy(durationSec = 99), 11))
        assertEquals(RecordingReserveResult.CONFLICT, repo.reserve(spec.copy(actorId = editor), 11))
        assertEquals(RecordingReserveResult.CONFLICT, repo.reserve(spec.copy(manifest = ByteArray(40) { 1 }), 11))
        assertEquals(RecordingReserveResult.CONFLICT, repo.reserve(spec.copy(chunks = listOf(
            RecordingChunkSpec(0, 40, "0".repeat(64)),
        )), 11))
    }

    @Test
    fun `reservation quota consumes incomplete bytes and rolls back failed reservation`() = withTestDb { db ->
        seed(db)
        val repo = TeamRecordingRepository(db, maxScopeBytes = 4257, maxAccountBytes = 4257)
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(reservation(), 10))
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(reservation("rec-2"), 11))
        val failure = assertFailsWith<StorageQuotaExceededException> { repo.reserve(reservation("rec-3"), 12) }
        assertEquals(StorageQuota.SPACE, failure.quota)
        assertTrue(repo.delete("team-1", "rec-1", owner))
        assertEquals(RecordingReserveResult.CREATED, repo.reserve(reservation("rec-3"), 13))
    }

    @Test
    fun `finalize appends one actor stamped activity and cannot extend retention`() = withTestDb { db ->
        val repo = seed(db)
        repo.reserve(reservation(), 10)
        assertFalse(repo.complete("team-1", "rec-1", owner, 11))
        assertEquals(RecordingChunkResult.CONFLICT, repo.putChunk("team-1", "rec-1", owner, 0, ByteArray(40), now = 11))
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        assertTrue(repo.complete("team-1", "rec-1", owner, 20))
        assertTrue(repo.complete("team-1", "rec-1", owner, 30))
        assertEquals(20 + 30 * 86_400_000L, repo.ready("team-1", "rec-1", now = 30)!!.expiresAt)
        dbTransaction(db) {
            val activity = ActivityLog.selectAll().where { ActivityLog.recordingId eq "rec-1" }.toList()
            assertEquals(1, activity.size)
            assertEquals(owner, activity.single()[ActivityLog.accountId])
            assertEquals(20L, activity.single()[ActivityLog.durationSec])
        }
    }

    @Test
    fun `expiry blocks playback and resurrecting incomplete upload before cleanup`() = withTestDb { db ->
        val repo = seed(db)
        repo.reserve(reservation(), 10)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        assertFalse(repo.complete("team-1", "rec-1", owner, 10 + 86_400_000))
        assertEquals(RecordingReserveResult.CONFLICT, repo.reserve(reservation(), 10 + 86_400_000))
        assertEquals(1, repo.cleanupExpired(10 + 86_400_000))
        repo.reserve(reservation(), 100)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 101)
        repo.complete("team-1", "rec-1", owner, 102)
        val expiry = 102 + 30 * 86_400_000L
        assertNull(repo.ready("team-1", "rec-1", owner, now = expiry))
        assertNull(repo.chunk("team-1", "rec-1", 0, owner, now = expiry))
        assertTrue(repo.listReady("team-1", "", owner, now = expiry).isEmpty())
        assertEquals(1, repo.cleanupExpired(expiry))
    }

    @Test
    fun `bulk deletion is owner only and deletes manifest and chunks together`() = withTestDb { db ->
        val repo = seed(db)
        repo.reserve(reservation(), 10)
        repo.reserve(reservation("rec-2"), 10)
        assertNull(repo.deleteMany("team-1", listOf("rec-1", "rec-2"), editor))
        assertEquals(2, repo.deleteMany("team-1", listOf("rec-1", "rec-2", "missing"), owner))
        dbTransaction(db) {
            assertEquals(2L, TeamRecordings.selectAll().count())
            assertTrue(TeamRecordings.selectAll().all { it[TeamRecordings.chunkCount] == 0 })
            assertEquals(0L, TeamRecordingChunks.selectAll().count())
        }
    }



    @Test
    fun `host type deleted state and claimed scope are authoritative`() = withTestDb { db ->
        val repo = seed(db)
        TeamRecordRepository(db).upsert("team-1", "", listOf(
            IncomingRecord("snippet-1", "SNIPPET", 1, "2026-07-04T00:00:00Z", "dev-a", false, byteArrayOf(1)),
            IncomingRecord("deleted-host", "HOST", 1, "2026-07-04T00:00:00Z", "dev-a", true, byteArrayOf(1)),
        ))
        assertEquals(RecordingReserveResult.NO_HOST, repo.reserve(reservation().copy(hostId = "snippet-1"), 10))
        assertEquals(RecordingReserveResult.NO_HOST, repo.reserve(reservation().copy(hostId = "deleted-host"), 10))
        TeamScopeRepository(db).create("team-1", "prod", owner, byteArrayOf(1), 3)
        repo.putPolicy("team-1", "prod", owner,
            signedPolicyFixture(TeamRepository(db), owner, "team-1", "prod"), 5)
        assertEquals(RecordingReserveResult.NO_HOST, repo.reserve(reservation(scope = "prod"), 10))
    }

    @Test
    fun `bounds reject chunk indices hashes manifest and whole recording before writes`() = withTestDb { db ->
        val repo = seed(db)
        val spec = reservation()
        for (bad in listOf(
            spec.copy(chunks = emptyList()),
            spec.copy(chunks = listOf(RecordingChunkSpec(1, 40, sha256Hex(bytes)))),
            spec.copy(chunks = listOf(RecordingChunkSpec(0, 39, sha256Hex(bytes)))),
            spec.copy(chunks = listOf(RecordingChunkSpec(0, TeamRecordingRepository.MAX_CHUNK_BYTES + 1, sha256Hex(bytes)))),
            spec.copy(chunks = listOf(RecordingChunkSpec(0, 40, "X".repeat(64)))),
            spec.copy(chunks = List(129) { RecordingChunkSpec(it, 40, sha256Hex(bytes)) }),
            spec.copy(manifest = ByteArray(65_537)),
            spec.copy(wrappedKey = ByteArray(257)),
            spec.copy(durationSec = -1),
        )) assertEquals(RecordingReserveResult.TOO_LARGE, repo.reserve(bad, 10))
        dbTransaction(db) { assertEquals(0L, TeamRecordings.selectAll().count()) }
    }

    @Test
    fun `owner account quota includes recording metadata and inherited team recordings`() = withTestDb { db ->
        val repo = seed(db)
        repo.reserve(reservation(), 10)
        val inventory = StatsRepository(db).inventory("jdbc:sqlite:test", now = 11)
        assertEquals(1L, inventory.recordingRows)
        assertEquals(1104L, inventory.recordingStorageBytes)
        assertEquals(1616L, inventory.recordingReservedBytes)
        AdminRepository(db).deleteAccount(owner)
        assertEquals(editor, TeamRepository(db).team("team-1")!!.ownerAccountId)
        assertFalse(repo.delete("team-1", "rec-1", owner))
        val limited = TeamRecordingRepository(db, maxAccountBytes = 1616)
        val failure = assertFailsWith<StorageQuotaExceededException> {
            limited.reserve(reservation("rec-2").copy(actorId = editor), 12)
        }
        assertEquals(StorageQuota.ACCOUNT, failure.quota)
        assertTrue(repo.delete("team-1", "rec-1", editor))
        AdminRepository(db).deleteAccount(editor)
        assertNull(repo.policy("team-1", ""))
        dbTransaction(db) {
            assertEquals(0L, TeamRecordings.selectAll().count())
            assertEquals(0L, TeamRecordingChunks.selectAll().count())
        }
    }

    @Test
    fun `old policy cannot authorize upload after key rotation`() = withTestDb { db ->
        val repo = seed(db)
        repo.reserve(reservation(), 10)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        assertEquals(RekeyOutcome.OK, TeamRepository(db).rekey("team-1", 1, mapOf(owner to ByteArray(40))))
        assertEquals(RecordingReserveResult.CONFLICT, repo.reserve(reservation("rec-2"), 12))
        assertFalse(repo.complete("team-1", "rec-1", owner, 12))
    }

    @Test
    fun `team deletion removes ciphertext policy and reservation`() = withTestDb { db ->
        val repo = seed(db)
        repo.reserve(reservation(), 10)
        repo.putChunk("team-1", "rec-1", owner, 0, bytes, now = 11)
        assertTrue(TeamRepository(db).deleteTeam("team-1"))
        assertNull(repo.policy("team-1", ""))
        dbTransaction(db) {
            assertEquals(0L, TeamRecordings.selectAll().count())
            assertEquals(0L, TeamRecordingChunks.selectAll().count())
        }
    }

    @Test
    fun `staged wrap accepts only manager next epoch and scope deletion cascades`() = withTestDb { db ->
        val repo = seed(db, "prod")
        repo.reserve(reservation(scope = "prod"), 10)
        assertFalse(repo.stageWrap("team-1", "rec-1", editor, 1, ByteArray(40)))
        assertFalse(repo.stageWrap("team-1", "rec-1", owner, 2, ByteArray(40)))
        assertTrue(repo.stageWrap("team-1", "rec-1", owner, 1, ByteArray(40)))
        TeamScopeRepository(db).deleteReturningGrantees("team-1", "prod")
        assertNull(repo.policy("team-1", "prod"))
        dbTransaction(db) {
            assertEquals(0L, TeamRecordings.selectAll().count())
            assertEquals(0L, TeamRecordingChunks.selectAll().count())
        }
    }
}
