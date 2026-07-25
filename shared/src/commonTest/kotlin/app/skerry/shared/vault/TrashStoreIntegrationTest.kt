package app.skerry.shared.vault

import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.VaultTunnelStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deleting through the regular stores must land in the trash and come back intact. */
class TrashStoreIntegrationTest {

    private val vault = FakeVault()
    private val trash = TrashStore(vault, now = { 1_000L })

    @Test
    fun `removing a host captures it and restore returns it to the tree`() {
        val store = VaultHostStore(vault, trash = trash)
        store.put(Host(id = "a", label = "Web", address = "a.example.com", port = 22, username = "root"))
        store.put(Host(id = "b", label = "Db", address = "b.example.com", port = 22, username = "root"))

        store.remove("a")
        assertEquals(listOf("b"), store.all().map { it.id })

        val entry = trash.entries().single()
        assertEquals(RecordType.HOST, entry.originType)
        assertEquals("Web", entry.label)

        assertTrue(trash.restore(entry.recordId))
        // Back in the list with its profile intact; order is rebuilt with the restored host appended.
        assertEquals(setOf("a", "b"), store.all().map { it.id }.toSet())
        assertEquals("a.example.com", store.all().first { it.id == "a" }.address)
        assertEquals(listOf("b", "a"), WorkspaceLayoutStore(vault).read().hostOrder)
    }

    @Test
    fun `removing a credential keeps the secret recoverable`() {
        val store = CredentialStore(vault, trash)
        store.put(Credential("c-1", "Deploy key", CredentialSecret.Password("s3cret")))

        store.remove("c-1")
        assertNull(store.get("c-1"))

        assertTrue(trash.restore(trash.entries().single().recordId))
        assertEquals(CredentialSecret.Password("s3cret"), store.get("c-1")?.secret)
        assertEquals("Deploy key", store.get("c-1")?.label)
    }

    @Test
    fun `removing a snippet or a tunnel captures both`() {
        val snippets = VaultSnippetStore(vault, trash)
        val tunnels = VaultTunnelStore(vault, trash)
        snippets.put(Snippet("s-1", "Tail log", "tail -f /var/log/syslog"))
        tunnels.put(Tunnel("t-1", "DB", hostId = "h-1", direction = TunnelDirection.Local, bindPort = 5432))

        snippets.remove("s-1")
        tunnels.remove("t-1")

        assertEquals(setOf(RecordType.SNIPPET, RecordType.TUNNEL), trash.entries().map { it.originType }.toSet())
        trash.entries().forEach { assertTrue(trash.restore(it.recordId)) }
        assertEquals("tail -f /var/log/syslog", snippets.all().single().command)
        assertEquals(5432, tunnels.all().single().bindPort)
    }

    @Test
    fun `a store without a trash deletes as before`() {
        val store = VaultSnippetStore(vault, trash = null)
        store.put(Snippet("s-1", "Tail log", "tail -f"))
        store.remove("s-1")

        assertTrue(store.all().isEmpty())
        assertTrue(trash.entries().isEmpty(), "no snapshot when the store has no trash")
    }
}
