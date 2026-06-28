package app.skerry.shared.ssh

import app.skerry.shared.vault.FakeVault
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultKnownHostsStoreTest {

    private fun kh(host: String, port: Int = 22, keyType: String = "ssh-ed25519", fp: String = "SHA256:AAA") =
        KnownHost(host, port, keyType, fp, firstSeen = "2026-06-29T00:00:00Z")

    @Test
    fun `add then all returns the key`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("web.example.com"))
        assertEquals(1, store.all().size)
        assertEquals("web.example.com", store.all().single().host)
    }

    @Test
    fun `replace upserts the same identity rather than adding a second record`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("nas", fp = "SHA256:OLD"))
        store.replace(kh("nas", fp = "SHA256:NEW"))
        assertEquals(1, store.all().size)
        assertEquals("SHA256:NEW", store.all().single().fingerprint)
    }

    @Test
    fun `same host different port or keyType are distinct identities`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("h", port = 22, keyType = "ssh-ed25519"))
        store.add(kh("h", port = 2222, keyType = "ssh-ed25519"))
        store.add(kh("h", port = 22, keyType = "rsa-sha2-512"))
        assertEquals(3, store.all().size)
    }

    @Test
    fun `remove forgets the key by identity`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("a")); store.add(kh("b"))
        store.remove("a", 22, "ssh-ed25519")
        assertEquals(listOf("b"), store.all().map { it.host })
    }

    @Test
    fun `keys survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultKnownHostsStore(vault).add(kh("persisted"))
        assertEquals(listOf("persisted"), VaultKnownHostsStore(vault).all().map { it.host })
    }
}
