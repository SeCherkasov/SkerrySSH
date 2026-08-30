package app.skerry.shared.snippet

import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultSnippetStoreTest {

    private fun snippet(id: String, label: String = id) =
        Snippet(id = id, label = label, command = "echo $id", tags = listOf("ops"))

    @Test
    fun `put then all returns the snippet`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(snippet("s1", "Disk"))
        assertEquals(listOf("s1"), store.all().map { it.id })
        assertEquals("echo s1", store.all().single().command)
    }

    @Test
    fun `put upserts and remove tombstones`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(snippet("s1", "Old"))
        store.put(snippet("s1", "New"))
        assertEquals(listOf("New"), store.all().map { it.label })
        store.remove("s1")
        assertEquals(emptyList(), store.all().map { it.id })
    }

    @Test
    fun `entries survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultSnippetStore(vault).put(snippet("s1"))
        assertEquals(listOf("s1"), VaultSnippetStore(vault).all().map { it.id })
    }

    @Test
    fun `put preserves notes`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(Snippet(id = "s1", label = "Disk", command = "df -h", notes = "Check root partition usage"))
        assertEquals("Check root partition usage", store.all().single().notes)
    }

    @Test
    fun `a snippet written before folders existed reads back unfiled`() {
        val vault = FakeVault()
        // A payload from a client predating the field: no "group" key at all. The decoder has to
        // read it as unfiled, not fail the record and take the snippet with it.
        vault.put(
            "s1",
            RecordType.SNIPPET,
            """{"id":"s1","label":"Disk","command":"df -h","tags":["ops"]}""".encodeToByteArray(),
        )

        val stored = VaultSnippetStore(vault).all().single()
        assertEquals("Disk", stored.label)
        assertNull(stored.group)
    }

    @Test
    fun `reorder updates and persists order in layout`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1"))
        store.put(snippet("s2"))
        store.put(snippet("s3"))

        assertEquals(listOf("s1", "s2", "s3"), store.all().map { it.id })

        store.reorder { listOf(it[2], it[0], it[1]) }

        val freshStore = VaultSnippetStore(vault)
        assertEquals(listOf("s3", "s1", "s2"), freshStore.all().map { it.id })
    }
}
