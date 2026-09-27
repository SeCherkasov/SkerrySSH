package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A pull answers one page of the delta, not all of it: the client refuses a response past 32 MiB,
 * so an unpaged delta left a large vault — or a share space an editor had filled — unpullable for
 * every device starting from zero. And what a vault or a space may hold at all is bounded, so one
 * member cannot grow a space until every other member's device has to take it in.
 */
class RecordPagingTest {

    private val alice = "alice@example.com"
    private val teamWide = ""

    private fun rec(id: String, size: Int, version: Long = 1, deleted: Boolean = false) =
        IncomingRecord(id, "HOST", version, "2026-09-27T00:00:00Z", "devA", deleted, ByteArray(size) { 7 })

    /** Every page from [since] until an empty one, as the client's drain loop walks them. */
    private suspend fun drain(since: Long = 0, pull: suspend (Long) -> Pair<List<StoredRecord>, Long>): List<List<StoredRecord>> {
        val pages = mutableListOf<List<StoredRecord>>()
        var cursor = since
        while (true) {
            val (records, next) = pull(cursor)
            if (records.isEmpty()) return pages
            pages += records
            assertTrue(next > cursor, "a page that does not advance the cursor loops the client forever")
            cursor = next
        }
    }

    @Test
    fun `an account delta is paged by record count`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db, page = PullPageLimits(maxRecords = 3, maxBytes = 1_000_000))
        repo.upsert(alice, (1..7).map { rec("r$it", 10) })

        val pages = drain { since -> repo.delta(alice, since).let { it to (it.lastOrNull()?.serverSeq ?: since) } }

        assertEquals(listOf(3, 3, 1), pages.map { it.size })
        assertEquals((1..7).map { "r$it" }, pages.flatten().map { it.id })
    }

    @Test
    fun `an account page stops before the record that would take it past the byte budget`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db, page = PullPageLimits(maxRecords = 100, maxBytes = 1_000))
        repo.upsert(alice, (1..5).map { rec("r$it", 400) })

        val pages = drain { since -> repo.delta(alice, since).let { it to (it.lastOrNull()?.serverSeq ?: since) } }

        assertEquals(listOf(2, 2, 1), pages.map { it.size })
    }

    @Test
    fun `a record larger than the byte budget still comes through on a page of its own`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db, page = PullPageLimits(maxRecords = 100, maxBytes = 1_000))
        repo.upsert(alice, listOf(rec("small", 10), rec("big", 5_000), rec("after", 10)))

        val pages = drain { since -> repo.delta(alice, since).let { it to (it.lastOrNull()?.serverSeq ?: since) } }

        assertEquals(listOf(listOf("small"), listOf("big"), listOf("after")), pages.map { page -> page.map { it.id } })
    }

    @Test
    fun `a team space delta is paged and resumes from the last record it delivered`() = withTestDb { db ->
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, now = 10)
        val repo = TeamRecordRepository(db, page = PullPageLimits(maxRecords = 2, maxBytes = 1_000_000))
        repo.upsert("team-1", teamWide, (1..5).map { rec("r$it", 10) })

        val pages = drain { since -> repo.delta("team-1", teamWide, since).let { it.records to it.cursor } }

        assertEquals(listOf(2, 2, 1), pages.map { it.size })
        assertEquals((1..5).map { "r$it" }, pages.flatten().map { it.id })
    }

    @Test
    fun `a team space page stops at the byte budget`() = withTestDb { db ->
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, now = 10)
        val repo = TeamRecordRepository(db, page = PullPageLimits(maxRecords = 100, maxBytes = 1_000))
        repo.upsert("team-1", teamWide, (1..3).map { rec("r$it", 600) })

        val pages = drain { since -> repo.delta("team-1", teamWide, since).let { it.records to it.cursor } }

        assertEquals(listOf(1, 1, 1), pages.map { it.size })
    }

    @Test
    fun `a push that grows an account past its quota is refused and changes nothing`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db, maxAccountBytes = 1_000)
        repo.upsert(alice, listOf(rec("r1", 600)))

        assertFailsWith<StorageQuotaExceededException> { repo.upsert(alice, listOf(rec("r2", 600))) }

        assertEquals(listOf("r1"), repo.delta(alice, 0).map { it.id }, "the refused push must roll back whole")
    }

    @Test
    fun `an account over its quota can still shrink, delete and re-push what it holds`() = withTestDb { db ->
        seedAccount(db)
        RecordRepository(db).upsert(alice, listOf(rec("r1", 800), rec("r2", 800)))
        val repo = RecordRepository(db, maxAccountBytes = 1_000)

        // The client pushes every record on every cycle: an unchanged vault must keep syncing.
        assertEquals(false, repo.upsert(alice, listOf(rec("r1", 800), rec("r2", 800))).changed)
        repo.upsert(alice, listOf(rec("r1", 100, version = 2)))
        repo.upsert(alice, listOf(rec("r2", 0, version = 2, deleted = true)))
        // Back under the cap, growth is allowed again up to it.
        repo.upsert(alice, listOf(rec("r3", 800)))
        assertFailsWith<StorageQuotaExceededException> { repo.upsert(alice, listOf(rec("r4", 200))) }
    }

    @Test
    fun `the quota counts one account, not every account on the server`() = withTestDb { db ->
        seedAccount(db, alice)
        seedAccount(db, "bob@example.com")
        val repo = RecordRepository(db, maxAccountBytes = 1_000)
        repo.upsert("bob@example.com", listOf(rec("b1", 900)))

        repo.upsert(alice, listOf(rec("a1", 900)))
    }

    @Test
    fun `a push that grows a team space past its quota is refused, other spaces are not`() = withTestDb { db ->
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, now = 10)
        val repo = TeamRecordRepository(db, maxScopeBytes = 1_000)
        repo.upsert("team-1", teamWide, listOf(rec("r1", 600)))

        val refused = assertFailsWith<StorageQuotaExceededException> { repo.upsert("team-1", teamWide, listOf(rec("r2", 600))) }
        assertEquals(StorageQuota.SPACE, refused.quota)
        assertEquals(listOf("r1"), repo.delta("team-1", teamWide, 0).records.map { it.id })

        repo.upsert("team-1", "scope-a", listOf(rec("s1", 600)))
        repo.upsert("team-1", teamWide, listOf(rec("r1", 0, version = 2, deleted = true)))
        repo.upsert("team-1", teamWide, listOf(rec("r2", 600)))
    }

    @Test
    fun `teams count towards their owner's quota, however many of them there are`() = withTestDb { db ->
        seedAccount(db, alice)
        TeamRepository(db).create("team-1", alice, now = 10)
        TeamRepository(db).create("team-2", alice, now = 10)
        val vault = RecordRepository(db, maxAccountBytes = 1_000)
        val teams = TeamRecordRepository(db, maxAccountBytes = 1_000)
        vault.upsert(alice, listOf(rec("v1", 400)))
        teams.upsert("team-1", teamWide, listOf(rec("t1", 400)))

        // Each space alone is well under any cap; together with the vault they are not.
        val refused = assertFailsWith<StorageQuotaExceededException> { teams.upsert("team-2", "scope-a", listOf(rec("t2", 400))) }
        assertEquals(StorageQuota.ACCOUNT, refused.quota, "the owner's cap, not the space's, is what this push ran into")
        assertFailsWith<StorageQuotaExceededException> { vault.upsert(alice, listOf(rec("v2", 400))) }
        assertEquals(emptyList(), teams.delta("team-2", "scope-a", 0).records.map { it.id })
    }

    @Test
    fun `a team's bytes are charged to its owner, not to the member who pushed them`() = withTestDb { db ->
        seedAccount(db, alice)
        seedAccount(db, "bob@example.com")
        TeamRepository(db).create("team-1", alice, now = 10)
        val vault = RecordRepository(db, maxAccountBytes = 1_000)
        TeamRecordRepository(db, maxAccountBytes = 1_000).upsert("team-1", teamWide, listOf(rec("t1", 900)))

        vault.upsert("bob@example.com", listOf(rec("b1", 900)))
        assertFailsWith<StorageQuotaExceededException> { vault.upsert(alice, listOf(rec("a1", 200))) }
    }
}
