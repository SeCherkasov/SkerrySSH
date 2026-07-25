package app.skerry.ui.vault

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashEntry
import app.skerry.shared.vault.TrashSource
import app.skerry.shared.vault.trashRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrashControllerTest {

    private val day = 24 * 60 * 60 * 1000L

    private class FakeTrash : TrashSource {
        val stored = mutableListOf<TrashEntry>()
        var purgedExpired = 0
        var failRestore = false
        override fun entries(): List<TrashEntry> = stored.toList()
        override fun restore(recordId: String): Boolean {
            if (failRestore) return false
            return stored.removeAll { it.recordId == recordId }
        }
        override fun purge(recordId: String) { stored.removeAll { it.recordId == recordId } }
        override fun emptyAll() = stored.clear()
        override fun purgeExpired(): Int { purgedExpired++; return 0 }
    }

    private fun entry(id: String, deletedAt: Long, type: RecordType = RecordType.HOST) =
        TrashEntry(id, type, label = id.uppercase(), deletedAt = deletedAt, originVersion = 1, payload = "{}")

    private fun controller(source: FakeTrash, now: Long = 30 * day, onRestored: () -> Unit = {}) =
        TrashController(source, retentionMillis = 30 * day, now = { now }, onRestored = onRestored)

    @Test
    fun `refresh purges expired entries and projects the rest without their payload`() {
        val source = FakeTrash().apply { stored += entry("h1", deletedAt = 29 * day) }
        val controller = controller(source)

        controller.refresh()

        assertEquals(1, source.purgedExpired, "opening the trash is when expiry is applied")
        val item = controller.items.single()
        assertEquals("H1", item.label)
        assertEquals(RecordType.HOST, item.type)
        assertEquals(trashRecordId(RecordType.HOST, "h1"), item.recordId)
    }

    @Test
    fun `days left counts down the retention window and never goes below one`() {
        val source = FakeTrash().apply {
            stored += entry("fresh", deletedAt = 30 * day)
            stored += entry("old", deletedAt = 1 * day)
            stored += entry("expiring", deletedAt = 0)
        }
        val controller = controller(source, now = 30 * day)
        controller.refresh()

        val byId = controller.items.associateBy { it.originId }
        assertEquals(30, byId.getValue("fresh").daysLeft)
        assertEquals(1, byId.getValue("old").daysLeft)
        assertEquals(1, byId.getValue("expiring").daysLeft, "an entry about to expire still reads as a day left")
    }

    @Test
    fun `restore reloads the list and notifies the app so managers refresh`() {
        val source = FakeTrash().apply { stored += entry("h1", deletedAt = 30 * day) }
        var notified = 0
        val controller = controller(source, onRestored = { notified++ })
        controller.refresh()

        assertTrue(controller.restore(controller.items.single()))

        assertTrue(controller.items.isEmpty())
        assertEquals(1, notified)
    }

    @Test
    fun `a failed restore does not notify the app`() {
        val source = FakeTrash().apply { stored += entry("h1", deletedAt = 30 * day); failRestore = true }
        var notified = 0
        val controller = controller(source, onRestored = { notified++ })
        controller.refresh()

        assertFalse(controller.restore(controller.items.single()))

        assertEquals(0, notified)
        assertEquals(1, controller.items.size, "the entry stays in the list")
    }

    @Test
    fun `purge and empty drop entries without notifying the app`() {
        val source = FakeTrash().apply {
            stored += entry("a", deletedAt = 30 * day)
            stored += entry("b", deletedAt = 30 * day)
        }
        var notified = 0
        val controller = controller(source, onRestored = { notified++ })
        controller.refresh()

        controller.purge(controller.items.first { it.originId == "a" })
        assertEquals(listOf("b"), controller.items.map { it.originId })

        controller.emptyAll()
        assertTrue(controller.items.isEmpty())
        assertEquals(0, notified, "nothing came back, so no manager reload is needed")
    }
}
