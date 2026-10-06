package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamRecordingRepositoryTest {
    private val alice = "alice@example.com"

    @Test
    fun `upload is immutable and READY only after all hashes match`() = withTestDb { db ->
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, 10)
        TeamRecordRepository(db).upsert("team-1", "", listOf(
            IncomingRecord("host-1", "HOST", 1, "2026-07-04T00:00:00Z", "dev-a", false, byteArrayOf(1)),
        ))
        val repo = TeamRecordingRepository(db)
        assertEquals(RecordingPolicyResult.UPDATED, repo.putPolicy("team-1", "", alice,
            signedPolicyFixture(TeamRepository(db), alice, "team-1", retentionDays = 12), 50))
        val bytes = ByteArray(40) { it.toByte() }
        val digest = sha256Hex(bytes)
        val spec = RecordingReservation("team-1", "", "rec-1", "host-1", alice, 0, ByteArray(40),
            ByteArray(40), listOf(RecordingChunkSpec(0, bytes.size, digest)), 20, 12)

        assertEquals(RecordingReserveResult.CREATED, repo.reserve(spec, now = 100))
        assertNull(repo.ready("team-1", "rec-1", now = 201))
        assertFalse(repo.complete("team-1", "rec-1", alice, now = 200))
        assertEquals(RecordingChunkResult.CREATED, repo.putChunk("team-1", "rec-1", alice, 0, bytes, now = 150))
        assertEquals(RecordingChunkResult.SAME, repo.putChunk("team-1", "rec-1", alice, 0, bytes, now = 150))
        assertEquals(RecordingChunkResult.CONFLICT, repo.putChunk("team-1", "rec-1", alice, 0, byteArrayOf(9), now = 150))
        assertTrue(repo.complete("team-1", "rec-1", alice, now = 200))
        assertTrue(repo.complete("team-1", "rec-1", alice, now = 201))
        val ready = assertNotNull(repo.ready("team-1", "rec-1", now = 201))
        assertContentEquals(bytes, repo.chunk("team-1", "rec-1", 0, now = 201))
        assertEquals(200 + 12L * 86_400_000, ready.expiresAt)
        assertEquals(1, repo.listReady("team-1", "", now = 201).size)
    }

    @Test
    fun `reservation rejects a host outside the claimed scope`() = withTestDb { db ->
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, 10)
        val repo = TeamRecordingRepository(db)
        assertEquals(RecordingPolicyResult.UPDATED, repo.putPolicy("team-1", "", alice,
            signedPolicyFixture(TeamRepository(db), alice, "team-1"), 50))
        val bytes = ByteArray(40)
        val spec = RecordingReservation("team-1", "", "rec-1", "missing", alice, 0, ByteArray(40),
            ByteArray(40), listOf(RecordingChunkSpec(0, bytes.size, sha256Hex(bytes))), 0, 30)
        assertEquals(RecordingReserveResult.NO_HOST, repo.reserve(spec, now = 100))
    }
}
