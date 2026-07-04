package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamRecordRepositoryTest {

    private val alice = "alice@example.com"

    private fun rec(
        id: String,
        version: Long,
        deviceId: String = "devA",
        deleted: Boolean = false,
        updatedAt: String = "2026-07-04T00:00:00Z",
        blob: ByteArray = byteArrayOf(version.toByte()),
    ) = IncomingRecord(id, "HOST", version, updatedAt, deviceId, deleted, blob)

    private suspend fun seedTeam(db: org.jetbrains.exposed.sql.Database): TeamRecordRepository {
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, now = 10)
        return TeamRecordRepository(db)
    }

    @Test
    fun `upsert assigns monotonic teamSeq and delta follows the cursor`() = withTestDb { db ->
        val repo = seedTeam(db)

        val first = repo.upsert("team-1", listOf(rec("r1", 1), rec("r2", 1)))
        assertEquals(listOf(1L, 2L), first.records.map { it.serverSeq })
        assertEquals(2L, first.cursor)
        assertTrue(first.changed)

        assertEquals(listOf("r1", "r2"), repo.delta("team-1", 0).map { it.id })
        assertEquals(listOf("r2"), repo.delta("team-1", 1).map { it.id })

        val second = repo.upsert("team-1", listOf(rec("r1", 2)))
        assertEquals(3L, second.cursor)
        assertEquals(listOf("r2", "r1"), repo.delta("team-1", 1).map { it.id })
    }

    @Test
    fun `LWW rejects stale writes and no-op push does not advance the cursor`() = withTestDb { db ->
        val repo = seedTeam(db)
        repo.upsert("team-1", listOf(rec("r1", 5, blob = byteArrayOf(5))))

        val stale = repo.upsert("team-1", listOf(rec("r1", 3, blob = byteArrayOf(3))))
        assertEquals(5L, stale.records.single().version)
        assertContentEquals(byteArrayOf(5), stale.records.single().blob)
        assertFalse(stale.changed)
        assertEquals(1L, stale.cursor)
    }

    @Test
    fun `LWW breaks version ties by lexicographically greater deviceId`() = withTestDb { db ->
        val repo = seedTeam(db)
        repo.upsert("team-1", listOf(rec("r1", 7, deviceId = "devB", blob = byteArrayOf(11))))

        val lose = repo.upsert("team-1", listOf(rec("r1", 7, deviceId = "devA", blob = byteArrayOf(22))))
        assertEquals("devB", lose.records.single().deviceId)

        val win = repo.upsert("team-1", listOf(rec("r1", 7, deviceId = "devC", blob = byteArrayOf(33))))
        assertEquals("devC", win.records.single().deviceId)
    }

    @Test
    fun `records of different teams are isolated`() = withTestDb { db ->
        val repo = seedTeam(db)
        TeamRepository(db).create("team-2", alice, now = 11)

        repo.upsert("team-1", listOf(rec("r1", 1)))
        repo.upsert("team-2", listOf(rec("x1", 1)))

        assertEquals(listOf("r1"), repo.delta("team-1", 0).map { it.id })
        assertEquals(listOf("x1"), repo.delta("team-2", 0).map { it.id })
    }

    @Test
    fun `purgeTombstones removes only old tombstones`() = withTestDb { db ->
        val repo = seedTeam(db)
        repo.upsert(
            "team-1",
            listOf(
                rec("old-dead", 2, deleted = true, updatedAt = "2026-01-01T00:00:00Z"),
                rec("new-dead", 2, deleted = true, updatedAt = "2026-07-01T00:00:00Z"),
                rec("alive", 2, updatedAt = "2026-01-01T00:00:00Z"),
            ),
        )

        assertEquals(1, repo.purgeTombstones(beforeIso = "2026-04-01T00:00:00Z"))
        assertEquals(listOf("new-dead", "alive").sorted(), repo.delta("team-1", 0).map { it.id }.sorted())
    }
}
