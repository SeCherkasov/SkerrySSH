package app.skerry.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamRecordRepositoryTest {

    private val alice = "alice@example.com"

    /** Team-wide space; scopes are addressed by their id (see [TeamScopeRepositoryTest]). */
    private val teamWide = ""

    private fun rec(
        id: String,
        version: Long,
        deviceId: String = "devA",
        deleted: Boolean = false,
        updatedAt: String = "2026-07-04T00:00:00Z",
        blob: ByteArray = byteArrayOf(version.toByte()),
    ) = IncomingRecord(id, "HOST", version, updatedAt, deviceId, deleted, blob)

    private suspend fun seedTeam(db: org.jetbrains.exposed.v1.jdbc.Database): TeamRecordRepository {
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, now = 10)
        return TeamRecordRepository(db)
    }

    @Test
    fun `upsert assigns monotonic teamSeq and delta follows the cursor`() = withTestDb { db ->
        val repo = seedTeam(db)

        val first = repo.upsert("team-1", teamWide, listOf(rec("r1", 1), rec("r2", 1)))
        assertEquals(listOf(1L, 2L), first.records.map { it.serverSeq })
        assertEquals(2L, first.cursor)
        assertTrue(first.changed)

        assertEquals(listOf("r1", "r2"), repo.delta("team-1", teamWide, 0).records.map { it.id })
        assertEquals(listOf("r2"), repo.delta("team-1", teamWide, 1).records.map { it.id })

        val second = repo.upsert("team-1", teamWide, listOf(rec("r1", 2)))
        assertEquals(3L, second.cursor)
        assertEquals(listOf("r2", "r1"), repo.delta("team-1", teamWide, 1).records.map { it.id })
    }

    @Test
    fun `LWW rejects stale writes and no-op push does not advance the cursor`() = withTestDb { db ->
        val repo = seedTeam(db)
        repo.upsert("team-1", teamWide, listOf(rec("r1", 5, blob = byteArrayOf(5))))

        val stale = repo.upsert("team-1", teamWide, listOf(rec("r1", 3, blob = byteArrayOf(3))))
        assertEquals(5L, stale.records.single().version)
        assertContentEquals(byteArrayOf(5), stale.records.single().blob)
        assertFalse(stale.changed)
        assertEquals(1L, stale.cursor)
    }

    @Test
    fun `LWW breaks version ties by lexicographically greater deviceId`() = withTestDb { db ->
        val repo = seedTeam(db)
        repo.upsert("team-1", teamWide, listOf(rec("r1", 7, deviceId = "devB", blob = byteArrayOf(11))))

        val lose = repo.upsert("team-1", teamWide, listOf(rec("r1", 7, deviceId = "devA", blob = byteArrayOf(22))))
        assertEquals("devB", lose.records.single().deviceId)

        val win = repo.upsert("team-1", teamWide, listOf(rec("r1", 7, deviceId = "devC", blob = byteArrayOf(33))))
        assertEquals("devC", win.records.single().deviceId)
    }

    @Test
    fun `records of different teams are isolated`() = withTestDb { db ->
        val repo = seedTeam(db)
        TeamRepository(db).create("team-2", alice, now = 11)

        repo.upsert("team-1", teamWide, listOf(rec("r1", 1)))
        repo.upsert("team-2", teamWide, listOf(rec("x1", 1)))

        assertEquals(listOf("r1"), repo.delta("team-1", teamWide, 0).records.map { it.id })
        assertEquals(listOf("x1"), repo.delta("team-2", teamWide, 0).records.map { it.id })
    }

    @Test
    fun `records of different scopes are isolated within a team`() = withTestDb { db ->
        val repo = seedTeam(db)
        TeamScopeRepository(db).create("team-1", "prod", alice, byteArrayOf(1), now = 11)

        repo.upsert("team-1", teamWide, listOf(rec("shared", 1)))
        repo.upsert("team-1", "prod", listOf(rec("secret", 1)))

        assertEquals(listOf("shared"), repo.delta("team-1", teamWide, 0).records.map { it.id })
        assertEquals(listOf("secret"), repo.delta("team-1", "prod", 0).records.map { it.id })
    }

    @Test
    fun `a push cannot move a record into another scope`() = withTestDb { db ->
        // A member still holding a stale local copy of a record that has since moved into a scope
        // pushes it every sync cycle. Applying it would overwrite the scope's ciphertext with one
        // encrypted under a key the scope's members don't have — the record would go unreadable
        // for everyone. Such a write is skipped, leaving the stored scope authoritative.
        val repo = seedTeam(db)
        TeamScopeRepository(db).create("team-1", "prod", alice, byteArrayOf(1), now = 11)
        repo.upsert("team-1", "prod", listOf(rec("h1", 1, blob = byteArrayOf(42))))

        val intruder = repo.upsert("team-1", teamWide, listOf(rec("h1", 99, blob = byteArrayOf(7))))

        assertTrue(intruder.records.isEmpty())
        assertFalse(intruder.changed)
        val stored = repo.delta("team-1", "prod", 0).records.single()
        assertEquals(1L, stored.version)
        assertContentEquals(byteArrayOf(42), stored.blob)
        assertTrue(repo.delta("team-1", teamWide, 0).records.isEmpty())
    }

    @Test
    fun `delta cursor skips over records of other scopes instead of rescanning them`() = withTestDb { db ->
        // The cursor is the team's current teamSeq, not the last delivered row: without that, records
        // the caller may not see would be re-scanned on every single pull.
        val repo = seedTeam(db)
        TeamScopeRepository(db).create("team-1", "prod", alice, byteArrayOf(1), now = 11)

        repo.upsert("team-1", "prod", listOf(rec("p1", 1)))
        val page = repo.upsert("team-1", teamWide, listOf(rec("s1", 1)))
        repo.upsert("team-1", "prod", listOf(rec("p2", 1)))

        val teamWidePage = repo.delta("team-1", teamWide, 0)
        assertEquals(listOf("s1"), teamWidePage.records.map { it.id })
        assertEquals(3L, teamWidePage.cursor)
        assertEquals(2L, page.cursor)
        assertTrue(repo.delta("team-1", teamWide, teamWidePage.cursor).records.isEmpty())
    }

    @Test
    fun `delta never reports a cursor covering a record it did not deliver`() = withTestDb { db ->
        // The counter is read before the rows, and the rows are bounded by it. Simulated here by
        // pinning the team counter below an existing record: under PostgreSQL's READ COMMITTED the
        // same skew happens for real when a push commits between the two statements. Without the
        // bound, the record below would be withheld yet declared covered — lost for that client.
        val repo = seedTeam(db)
        repo.upsert("team-1", teamWide, listOf(rec("r1", 1), rec("r2", 1)))
        dbTransaction(db) { Teams.update({ Teams.id eq "team-1" }) { it[teamSeq] = 1 } }

        val page = repo.delta("team-1", teamWide, 0)

        assertEquals(listOf("r1"), page.records.map { it.id })
        assertEquals(1L, page.cursor)
        // And the withheld record still arrives once the counter catches up.
        dbTransaction(db) { Teams.update({ Teams.id eq "team-1" }) { it[teamSeq] = 2 } }
        assertEquals(listOf("r2"), repo.delta("team-1", teamWide, page.cursor).records.map { it.id })
    }

    @Test
    fun `purgeTombstones removes only old tombstones`() = withTestDb { db ->
        val repo = seedTeam(db)
        repo.upsert(
            "team-1",
            teamWide,
            listOf(
                rec("old-dead", 2, deleted = true, updatedAt = "2026-01-01T00:00:00Z"),
                rec("new-dead", 2, deleted = true, updatedAt = "2026-07-01T00:00:00Z"),
                rec("alive", 2, updatedAt = "2026-01-01T00:00:00Z"),
            ),
        )

        assertEquals(1, repo.purgeTombstones(beforeIso = "2026-04-01T00:00:00Z"))
        assertEquals(
            listOf("new-dead", "alive").sorted(),
            repo.delta("team-1", teamWide, 0).records.map { it.id }.sorted(),
        )
    }
}
