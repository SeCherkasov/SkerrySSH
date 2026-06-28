package app.skerry.shared.tunnel

import app.skerry.shared.vault.FakeVault
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
