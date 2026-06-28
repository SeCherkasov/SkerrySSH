package app.skerry.shared.snippet

import app.skerry.shared.vault.FakeVault
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
