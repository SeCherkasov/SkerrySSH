package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRepositoryTest {

    private fun rec(
        id: String,
        version: Long,
        deviceId: String = "devA",
        deleted: Boolean = false,
        blob: ByteArray = byteArrayOf(version.toByte(), 0x55, 0x66),
    ) = IncomingRecord(id, "HOST", version, "2026-06-29T00:00:00Z", deviceId, deleted, blob)

    @Test
    fun `account summaries aggregate devices and records per account`() = withTestDb { db ->
        seedAccount(db, "alice@example.com")
        seedAccount(db, "bob@example.com")
        val devices = DeviceRepository(db)
        val records = RecordRepository(db)
        val admin = AdminRepository(db)

        devices.register("alice@example.com", "devA", "Laptop", platform = "Linux")
        devices.register("alice@example.com", "devB", "Phone", platform = "Android")
        devices.revoke("alice@example.com", "devB")
        devices.register("bob@example.com", "devC", "Desktop")

        records.upsert("alice@example.com", listOf(rec("r1", 1), rec("r2", 1)))
        records.upsert("alice@example.com", listOf(rec("r1", 2, deleted = true))) // tombstone

        val summaries = admin.accountSummaries().associateBy { it.id }

        val alice = summaries.getValue("alice@example.com")
        assertEquals(2, alice.devices)
        assertEquals(1, alice.activeDevices) // devB revoked
        assertEquals(2, alice.records)
        assertEquals(1, alice.tombstones)
        assertTrue(alice.storageBytes > 0)

        val bob = summaries.getValue("bob@example.com")
        assertEquals(1, bob.devices)
        assertEquals(1, bob.activeDevices)
        assertEquals(0, bob.records)
        assertEquals(0, bob.tombstones)
        assertEquals(0L, bob.storageBytes)
    }

    @Test
    fun `record envelopes expose metadata and a ciphertext preview, never content`() = withTestDb { db ->
        seedAccount(db)
        val records = RecordRepository(db)
        val admin = AdminRepository(db)
        records.upsert("alice@example.com", listOf(rec("r1", 1, blob = byteArrayOf(0xDE.toByte(), 0xAD.toByte()))))

        val env = admin.recordEnvelopes("alice@example.com").single()
        assertEquals("r1", env.id)
        assertEquals("HOST", env.type)
        assertEquals(2, env.blobBytes)
        assertEquals("de ad", env.previewHex)
        assertEquals(1L, env.serverSeq)
        assertFalse(env.deleted)
    }

    @Test
    fun `purge tombstones only removes those below the slowest device cursor`() = withTestDb { db ->
        seedAccount(db)
        val devices = DeviceRepository(db)
        val records = RecordRepository(db)
        val admin = AdminRepository(db)
        devices.register("alice@example.com", "devA", "Laptop")

        records.upsert("alice@example.com", listOf(rec("r1", 1), rec("r2", 1)))
        records.upsert("alice@example.com", listOf(rec("r1", 2, deleted = true))) // tombstone at serverSeq 3

        // Курсор устройства = 2: tombstone (seq 3) ещё не дочитан → не чистим (иначе воскреснет).
        devices.touch("alice@example.com", "devA", syncVersion = 2)
        assertEquals(0, admin.purgeTombstones("alice@example.com"))
        assertEquals(1, admin.accountSummaries().single().tombstones)

        // Курсор догнал tombstone → теперь безопасно чистить.
        devices.touch("alice@example.com", "devA", syncVersion = 3)
        assertEquals(1, admin.purgeTombstones("alice@example.com"))
        assertEquals(0, admin.accountSummaries().single().tombstones)
    }

    @Test
    fun `a never-synced device blocks purge, an account with no devices purges all`() = withTestDb { db ->
        seedAccount(db, "alice@example.com")
        seedAccount(db, "ghost@example.com")
        val devices = DeviceRepository(db)
        val records = RecordRepository(db)
        val admin = AdminRepository(db)

        // alice: одно устройство с курсором, второе без курсора (null) → watermark 0 → не чистим.
        devices.register("alice@example.com", "devA", "Laptop")
        devices.register("alice@example.com", "devB", "Phone") // никогда не синхронизировалось
        records.upsert("alice@example.com", listOf(rec("r1", 1)))
        records.upsert("alice@example.com", listOf(rec("r1", 2, deleted = true)))
        devices.touch("alice@example.com", "devA", syncVersion = 99)
        assertEquals(0, admin.purgeTombstones("alice@example.com"))

        // ghost: записи без единого устройства → воскрешать некому → чистим всё.
        records.upsert("ghost@example.com", listOf(rec("g1", 1)))
        records.upsert("ghost@example.com", listOf(rec("g1", 2, deleted = true)))
        assertEquals(1, admin.purgeTombstones("ghost@example.com"))
    }

    @Test
    fun `delete account cascades records devices pairing and reports missing`() = withTestDb { db ->
        seedAccount(db, "alice@example.com")
        seedAccount(db, "bob@example.com")
        val devices = DeviceRepository(db)
        val records = RecordRepository(db)
        val pairing = PairingRepository(db)
        val admin = AdminRepository(db)

        devices.register("alice@example.com", "devA", "Laptop")
        records.upsert("alice@example.com", listOf(rec("r1", 1)))
        pairing.create("code1", "alice@example.com", byteArrayOf(9), expiresAt = Long.MAX_VALUE)
        // соседний аккаунт не должен пострадать
        devices.register("bob@example.com", "devC", "Desktop")
        records.upsert("bob@example.com", listOf(rec("b1", 1, deviceId = "devC")))

        assertTrue(admin.deleteAccount("alice@example.com"))
        assertFalse(admin.deleteAccount("alice@example.com")) // уже нет

        assertEquals(null, AccountRepository(db).find("alice@example.com"))
        assertTrue(devices.list("alice@example.com").isEmpty())
        assertTrue(records.delta("alice@example.com", 0).isEmpty())

        // bob цел
        val remaining = admin.accountSummaries()
        assertContentEquals(listOf("bob@example.com"), remaining.map { it.id })
        assertEquals(1, remaining.single().devices)
        assertEquals(1, remaining.single().records)
    }
}
