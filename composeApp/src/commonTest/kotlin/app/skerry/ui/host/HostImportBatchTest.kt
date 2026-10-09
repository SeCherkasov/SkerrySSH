package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import kotlin.test.Test
import kotlin.test.assertEquals

class HostImportBatchTest {
    @Test
    fun `import canonicalizes tags and persists one batch before publishing`() {
        val store = BatchStore()
        val controller = HostManagerController(store) { error("ids already assigned") }
        val imported = listOf(
            Host("jump", "Jump", "jump.test", 22, "root", tags = listOf("#PROD", "prod")),
            Host("web", "Web", "web.test", 22, "root", jumpHostId = "jump"),
        )
        controller.importHosts(imported)
        assertEquals(1, store.batches)
        assertEquals(listOf("prod"), controller.hosts.first().tags)
        assertEquals("jump", controller.hosts.last().jumpHostId)
        assertEquals(store.all(), controller.hosts)
    }

    private class BatchStore : HostStore {
        private var rows = emptyList<Host>()
        var batches = 0
        override fun all() = rows
        override fun put(host: Host) = error("import must use the batch")
        override fun putAll(hosts: List<Host>) { batches++; rows = hosts }
        override fun remove(id: String) = Unit
        override fun reorder(transform: (List<Host>) -> List<Host>) { rows = transform(rows) }
    }
}
