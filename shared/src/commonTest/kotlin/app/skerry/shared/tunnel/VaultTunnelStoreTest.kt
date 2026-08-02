package app.skerry.shared.tunnel

import app.skerry.shared.vault.FakeVault
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultTunnelStoreTest {

    private fun tunnel(id: String, label: String = id) = Tunnel(
        id = id,
        label = label,
        hostId = "host-$id",
        direction = TunnelDirection.Local,
        bindPort = 8080,
        destHost = "127.0.0.1",
        destPort = 80,
    )

    @Test
    fun `put then all returns the tunnel`() {
        val store = VaultTunnelStore(FakeVault())
        store.put(tunnel("t1", "Prod DB"))
        assertEquals(listOf("t1"), store.all().map { it.id })
        assertEquals("host-t1", store.all().single().hostId)
    }

    @Test
    fun `put upserts and remove tombstones`() {
        val store = VaultTunnelStore(FakeVault())
        store.put(tunnel("t1", "Old"))
        store.put(tunnel("t1", "New"))
        assertEquals(listOf("New"), store.all().map { it.label })
        store.remove("t1")
        assertEquals(emptyList(), store.all().map { it.id })
    }

    @Test
    fun `entries survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultTunnelStore(vault).put(tunnel("t1"))
        assertEquals(listOf("t1"), VaultTunnelStore(vault).all().map { it.id })
    }

    @Test
    fun `autostart survives a round trip through the vault`() {
        val vault = FakeVault()
        VaultTunnelStore(vault).put(tunnel("t1").copy(autostart = true))
        assertTrue(VaultTunnelStore(vault).all().single().autostart)
    }

    @Test
    fun `a record written before autostart existed decodes as off`() {
        // The field arrived after tunnels shipped, so records already in a synced vault carry no
        // `autostart` key. They must decode, and decode to off — a tunnel nobody asked to raise
        // must not start dialling by itself after an update.
        val legacy = """{"id":"t1","label":"Prod DB","hostId":"h1","direction":"Local","bindHost":"127.0.0.1","bindPort":8080,"destHost":"10.0.0.5","destPort":80}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(Tunnel.serializer(), legacy)
        assertFalse(decoded.autostart)
    }
}
