package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `retention keeps only the most recent maxRows events`() = withTestDb { db ->
        val repo = ActivityRepository(db, maxRows = 3)
        repeat(5) { i -> repo.record("a", "sync.pull", "delta $i", now = i.toLong()) }

        val recent = repo.recent(100)
        assertEquals(3, recent.size)
        assertEquals(listOf("delta 4", "delta 3", "delta 2"), recent.map { it.detail })
    }
}
