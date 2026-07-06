package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordRepositoryTest {

    private fun rec(
        id: String,
        version: Long,
        deviceId: String = "devA",
        deleted: Boolean = false,
        blob: ByteArray = byteArrayOf(version.toByte()),
    ) = IncomingRecord(id, "HOST", version, "2026-06-29T00:00:00Z", deviceId, deleted, blob)

    @Test
    fun `upsert assigns monotonic serverSeq and delta returns changes since cursor`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db)

        val first = repo.upsert("alice@example.com", listOf(rec("r1", 1), rec("r2", 1)))
        assertEquals(listOf(1L, 2L), first.records.map { it.serverSeq })
        assertEquals(2L, first.cursor)

        // since=0 -> both records; since=1 -> only the second
        assertEquals(listOf("r1", "r2"), repo.delta("alice@example.com", 0).map { it.id })
        assertEquals(listOf("r2"), repo.delta("alice@example.com", 1).map { it.id })

        // updating r1 moves its serverSeq to the end of the delta
        val second = repo.upsert("alice@example.com", listOf(rec("r1", 2)))
        assertEquals(3L, second.records.single().serverSeq)
        assertEquals(3L, second.cursor)
        assertEquals(listOf("r2", "r1"), repo.delta("alice@example.com", 1).map { it.id })
    }

    @Test
    fun `LWW keeps higher version and rejects stale write`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db)
        repo.upsert("alice@example.com", listOf(rec("r1", 5, blob = byteArrayOf(5))))

        // an older version is rejected; the server returns its own state
        val result = repo.upsert("alice@example.com", listOf(rec("r1", 3, blob = byteArrayOf(3)))).records
        assertEquals(5L, result.single().version)
        assertContentEquals(byteArrayOf(5), result.single().blob)
        assertContentEquals(byteArrayOf(5), repo.delta("alice@example.com", 0).single().blob)
    }

    @Test
    fun `LWW tie broken by lexicographically greater deviceId`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db)
        repo.upsert("alice@example.com", listOf(rec("r1", 7, deviceId = "devB", blob = byteArrayOf(11))))

        // same version, greater deviceId -> wins
        val win = repo.upsert("alice@example.com", listOf(rec("r1", 7, deviceId = "devC", blob = byteArrayOf(22)))).records
        assertEquals("devC", win.single().deviceId)

        // same version, lesser deviceId -> loses
        val lose = repo.upsert("alice@example.com", listOf(rec("r1", 7, deviceId = "devA", blob = byteArrayOf(33)))).records
        assertEquals("devC", lose.single().deviceId)
        assertContentEquals(byteArrayOf(22), lose.single().blob)
    }

    @Test
    fun `tombstone delete is stored and surfaced in delta`() = withTestDb { db ->
        seedAccount(db)
        val repo = RecordRepository(db)
        repo.upsert("alice@example.com", listOf(rec("r1", 1)))
        repo.upsert("alice@example.com", listOf(rec("r1", 2, deleted = true)))

        val latest = repo.delta("alice@example.com", 0).single()
        assertTrue(latest.deleted)
    }
}
