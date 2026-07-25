package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrashStoreTest {

    private var clock = 1_000_000L
    private val vault = FakeVault()
    private fun trash(retention: Long = TrashStore.RETENTION_MILLIS) =
        TrashStore(vault, retentionMillis = retention, now = { clock })

    private fun payloadOf(id: String) = """{"id":"$id","label":"Web"}""".encodeToByteArray()

    private fun putHost(id: String) = vault.put(id, RecordType.HOST, payloadOf(id))

    @Test
    fun `capture snapshots the payload so a removed record can be restored under the same id`() {
        val store = trash()
        putHost("h1")

        assertTrue(store.capture("h1", RecordType.HOST, "Web"))
        vault.remove("h1")
        assertNull(vault.openPayload("h1"), "the record itself must be gone after remove")

        val entry = store.entries().single()
        assertEquals("h1", entry.originId)
        assertEquals(RecordType.HOST, entry.originType)
        assertEquals("Web", entry.label)

        assertTrue(store.restore(entry.recordId))
        assertEquals(payloadOf("h1").decodeToString(), vault.openPayload("h1")?.decodeToString())
        assertTrue(store.entries().isEmpty(), "a restored entry leaves the trash")
    }

    @Test
    fun `restore outranks a tombstone another device may still hold`() {
        val store = trash()
        putHost("h1") // version 1
        vault.put("h1", RecordType.HOST, payloadOf("h1")) // version 2
        store.capture("h1", RecordType.HOST, "Web")
        vault.remove("h1") // tombstone version 3
        // This device has already compacted the tombstone (the server said every device read it),
        // so a plain put would restart at version 1 — and lose LWW on a device that still holds
        // version 3, deleting the host again behind the user's back.
        vault.compact(listOf("h1"))

        store.restore(store.entries().single().recordId)

        val restored = vault.records().single { it.id == "h1" }
        assertTrue(restored.version > 3, "restored version ${restored.version} must beat the tombstone")
        assertFalse(restored.deleted)
    }

    @Test
    fun `trash records are invisible to the regular stores`() {
        val store = trash()
        putHost("h1")
        store.capture("h1", RecordType.HOST, "Web")
        vault.remove("h1")

        val liveHostRecords = vault.records().filter { it.type == RecordType.HOST && !it.deleted }
        assertTrue(liveHostRecords.isEmpty(), "the snapshot must not be stored as a HOST record")
        assertEquals(RecordType.TRASH, vault.records().single { !it.deleted }.type)
    }

    @Test
    fun `entries hides expired snapshots and purgeExpired tombstones them`() {
        val store = trash(retention = 1_000L)
        putHost("h1")
        store.capture("h1", RecordType.HOST, "Web")
        vault.remove("h1")

        clock += 1_001L
        assertTrue(store.entries().isEmpty(), "an expired entry is not offered for restore")
        assertEquals(1, store.purgeExpired())
        assertTrue(vault.records().single { it.type == RecordType.TRASH }.deleted, "purge tombstones the entry")
        assertEquals(0, store.purgeExpired(), "purge is idempotent")
    }

    @Test
    fun `entries lists the newest deletion first`() {
        val store = trash()
        putHost("a"); putHost("b")
        store.capture("a", RecordType.HOST, "A")
        vault.remove("a")
        clock += 5_000L
        store.capture("b", RecordType.HOST, "B")
        vault.remove("b")

        assertEquals(listOf("b", "a"), store.entries().map { it.originId })
    }

    @Test
    fun `deleting the same id twice keeps only the latest snapshot`() {
        val store = trash()
        putHost("h1")
        store.capture("h1", RecordType.HOST, "Old")
        vault.remove("h1")
        vault.put("h1", RecordType.HOST, """{"id":"h1","label":"New"}""".encodeToByteArray())
        store.capture("h1", RecordType.HOST, "New")
        vault.remove("h1")

        val entry = store.entries().single()
        assertEquals("New", entry.label)
        store.restore(entry.recordId)
        assertEquals("""{"id":"h1","label":"New"}""", vault.openPayload("h1")?.decodeToString())
    }

    @Test
    fun `capture refuses record types the trash does not cover`() {
        val store = trash()
        vault.put("k1", RecordType.KNOWN_HOST, "{}".encodeToByteArray())
        assertFalse(store.capture("k1", RecordType.KNOWN_HOST, "host"))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `capture of a missing or already deleted record is a no-op`() {
        val store = trash()
        assertFalse(store.capture("ghost", RecordType.HOST, "Ghost"))
        putHost("h1")
        vault.remove("h1")
        assertFalse(store.capture("h1", RecordType.HOST, "Web"), "a tombstone has no payload to snapshot")
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `capture on a locked vault reports failure instead of throwing`() {
        val store = trash()
        putHost("h1")
        vault.locked = true
        assertFalse(store.capture("h1", RecordType.HOST, "Web"))
        vault.locked = false
    }

    @Test
    fun `purge drops one entry and empty drops them all`() {
        val store = trash()
        putHost("a"); putHost("b")
        store.capture("a", RecordType.HOST, "A"); vault.remove("a")
        store.capture("b", RecordType.HOST, "B"); vault.remove("b")

        store.purge(store.entries().first { it.originId == "a" }.recordId)
        assertEquals(listOf("b"), store.entries().map { it.originId })

        store.emptyAll()
        assertTrue(store.entries().isEmpty())
        assertNull(vault.openPayload("b"), "a purged snapshot keeps no payload")
    }

    @Test
    fun `restore of an entry that is no longer in the trash fails`() {
        val store = trash()
        putHost("h1")
        store.capture("h1", RecordType.HOST, "Web")
        vault.remove("h1")
        val entry = store.entries().single()
        store.purge(entry.recordId)

        assertFalse(store.restore(entry.recordId), "a stale entry must not resurrect anything")
        assertNull(vault.openPayload("h1"))
    }
}
