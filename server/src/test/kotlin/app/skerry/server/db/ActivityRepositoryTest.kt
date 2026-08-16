package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRepositoryTest {

    @Test
    fun `records events and returns the most recent first`() = withTestDb { db ->
        val repo = ActivityRepository(db)
        repo.record("alice@example.com", "auth.login", "srp login", deviceId = "devA", now = 1_000)
        repo.record("alice@example.com", "sync.push", "2 records · cursor 4", deviceId = "devA", now = 2_000)

        val recent = repo.recent(10)
        assertEquals(listOf("sync.push", "auth.login"), recent.map { it.event })
        val newest = recent.first()
        assertEquals("devA", newest.deviceId)
        assertEquals(2_000, newest.createdAt)
        assertEquals("2 records · cursor 4", newest.detail)
    }

    @Test
    fun `event without a device is allowed`() = withTestDb { db ->
        val repo = ActivityRepository(db)
        repo.record("alice@example.com", "device.stale", "no sync for 6 days", now = 5_000)
        assertEquals(null, repo.recent(1).single().deviceId)
    }

    @Test
    fun `a team event carries its record subject`() = withTestDb { db ->
        val repo = ActivityRepository(db)
        repo.record(
            "alice@example.com", "team.record_change", "HOST h-1",
            teamId = "team-1", recordId = "h-1", recordType = "HOST", scopeId = "prod", now = 1_000,
        )

        val row = repo.recentForTeam("team-1").single()
        assertEquals("h-1", row.recordId)
        assertEquals("HOST", row.recordType)
        assertEquals("prod", row.scopeId)
        assertEquals(null, row.durationSec)
    }

    @Test
    fun `session events collapse repeats inside the dedup window`() = withTestDb { db ->
        // Reconnect storms (a flapping link reopens the session over and over) must not bury the
        // rest of the team's history under identical rows.
        val repo = ActivityRepository(db)
        val first = repo.recordTeamSession(
            "alice@example.com", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 100_000,
        )
        val repeat = repo.recordTeamSession(
            "alice@example.com", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 130_000,
        )
        assertTrue(first)
        assertFalse(repeat)
        assertEquals(1, repo.recentForTeam("team-1").size)

        // Past the window the next connect is news again.
        assertTrue(
            repo.recordTeamSession(
                "alice@example.com", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 300_000,
            ),
        )
        // Another host, another member, and another kind of event are all distinct subjects.
        assertTrue(
            repo.recordTeamSession(
                "alice@example.com", "team-1", "team.session_open", "h-2", "HOST", "", null, now = 300_100,
            ),
        )
        assertTrue(
            repo.recordTeamSession(
                "bob@example.com", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 300_200,
            ),
        )
        assertTrue(
            repo.recordTeamSession(
                "alice@example.com", "team-1", "team.session_record", "h-1", "HOST", "", 42, now = 300_300,
            ),
        )
        assertEquals(5, repo.recentForTeam("team-1").size)
        assertEquals(42, repo.recentForTeam("team-1").first().durationSec)
    }

    @Test
    fun `a clock that jumped backwards does not wedge the dedup shut`() = withTestDb { db ->
        // NTP correction or a suspend/resume can stamp a report earlier than the previous one. Treating
        // that as "recent" would silently drop genuine reports until the clock caught up again.
        val repo = ActivityRepository(db)
        assertTrue(repo.recordTeamSession("a", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 300_000))
        assertTrue(repo.recordTeamSession("a", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 280_000))
        assertEquals(2, repo.recentForTeam("team-1").size)
    }

    @Test
    fun `the same record in another space is a separate subject`() = withTestDb { db ->
        // A record can move between spaces (an unshare plus a share), and a session in its new space
        // is a different fact — not a duplicate of the one before the move.
        val repo = ActivityRepository(db)
        assertTrue(repo.recordTeamSession("a", "team-1", "team.session_open", "h-1", "HOST", "", null, now = 1_000))
        assertTrue(repo.recordTeamSession("a", "team-1", "team.session_open", "h-1", "HOST", "prod", null, now = 1_500))
        assertEquals(2, repo.recentForTeam("team-1").size)
    }

    /**
     * The same partition argument, one level down: team rows are kept per team, but the
     * account-level bucket was "every row with no team" across every account on the instance. One
     * account syncing all day therefore evicted everybody else's logins, device revocations and
     * password changes — the rows an audit trail exists for.
     */
    @Test
    fun `one account cannot evict another account's log`() = withTestDb { db ->
        val repo = ActivityRepository(db, maxRows = 3)
        repeat(2) { i -> repo.record("quiet@example.com", "auth.login", "quiet $i", now = i.toLong()) }

        repeat(20) { i -> repo.record("busy@example.com", "sync.push", "busy $i", now = 100L + i) }

        assertEquals(
            listOf("quiet 1", "quiet 0"),
            repo.recentForAccount("quiet@example.com").map { it.detail },
            "a busy account evicted another account's audit trail",
        )
        assertEquals(3, repo.recentForAccount("busy@example.com").size, "the busy account still trims its own")
    }

    /**
     * The ceiling over every account's rows together. Per-account retention alone lets the table
     * grow with the number of accounts, which on an instance with open registration is whatever
     * anyone cares to register — and the accounts that get there are small ones, none of which ever
     * trips its own cap.
     */
    @Test
    fun `a ceiling bounds the account log across accounts, not just per account`() = withTestDb { db ->
        val repo = ActivityRepository(db, maxRows = 100, accountRowsTotal = 6)

        repeat(4) { i -> repo.record("a@example.com", "auth.login", "a $i", now = i.toLong()) }
        repeat(4) { i -> repo.record("b@example.com", "auth.login", "b $i", now = 100L + i) }

        // Neither account came near its own cap, so only the global ceiling can have trimmed this.
        assertEquals(6, repo.recent(50).size)
        // The oldest rows went first, newest kept.
        assertEquals(listOf("b 3", "b 2", "b 1", "b 0", "a 3", "a 2"), repo.recent(50).map { it.detail })
    }

    @Test
    fun `one team cannot evict another team's history, nor the account log`() = withTestDb { db ->
        // Any active member (a viewer included) can append to their team's bucket by reporting
        // sessions. With one global cap that traffic would push everybody else's audit trail out.
        val repo = ActivityRepository(db, maxRows = 3, teamMaxRows = 2)
        repeat(3) { i -> repo.record("a", "auth.login", "login $i", now = i.toLong()) }
        repeat(2) { i -> repo.record("a", "team.record_change", "b $i", teamId = "team-b", now = 100L + i) }

        repeat(20) { i ->
            repo.recordTeamSession("flood", "team-a", "team.session_open", "h-$i", "HOST", "", null, now = 200L + i)
        }

        assertEquals(2, repo.recentForTeam("team-a").size) // the flooder only trims their own bucket
        assertEquals(listOf("b 1", "b 0"), repo.recentForTeam("team-b").map { it.detail })
        assertEquals(
            listOf("login 2", "login 1", "login 0"),
            repo.recent(50).filter { it.event == "auth.login" }.map { it.detail },
        )
    }

    @Test
    fun `a batch of events lands in one transaction`() = withTestDb { db ->
        val repo = ActivityRepository(db)
        repo.recordAll(
            listOf(
                ActivityEvent("a", "team.record_share", "HOST h-1", teamId = "team-1", recordId = "h-1"),
                ActivityEvent("a", "team.record_share", "HOST h-2", teamId = "team-1", recordId = "h-2"),
            ),
            now = 5_000,
        )
        val rows = repo.recentForTeam("team-1")
        assertEquals(listOf("h-2", "h-1"), rows.map { it.recordId })
        assertTrue(rows.all { it.createdAt == 5_000L })
    }

    @Test
    fun `retention keeps only the most recent maxRows events`() = withTestDb { db ->
        val repo = ActivityRepository(db, maxRows = 3)
        repeat(5) { i -> repo.record("a", "sync.pull", "delta $i", now = i.toLong()) }

        val recent = repo.recent(100)
        assertEquals(3, recent.size)
        assertEquals(listOf("delta 4", "delta 3", "delta 2"), recent.map { it.detail })
    }
}
