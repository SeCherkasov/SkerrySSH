package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TofuHostKeyVerifierTest {

    private val ed25519 = "ssh-ed25519"
    private val fpA = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val fpB = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun `accepts and stores the first key for a host`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)

        assertTrue(verifier.verify("example.com", 22, ed25519, fpA))

        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
    }

    @Test
    fun `accepts a matching key on subsequent connects`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertTrue(verifier.verify("example.com", 22, ed25519, fpA))
        // Не дублируем уже известную запись.
        assertEquals(1, store.all().size)
    }

    @Test
    fun `rejects a changed fingerprint for a known host key`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertFalse(verifier.verify("example.com", 22, ed25519, fpB))
        // Отказ не перезаписывает доверенный ключ.
        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
    }

    @Test
    fun `treats different ports as different hosts`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertTrue(verifier.verify("example.com", 2222, ed25519, fpB))
        assertEquals(2, store.all().size)
    }

    @Test
    fun `tracks a different key type for the same host independently`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertTrue(verifier.verify("example.com", 22, "rsa-sha2-512", fpB))
        assertEquals(2, store.all().size)
    }
}

/** Хранилище в памяти для тестов TOFU-логики. */
private class InMemoryKnownHostsStore : KnownHostsStore {
    private val entries = mutableListOf<KnownHost>()
    override fun all(): List<KnownHost> = entries.toList()
    override fun add(host: KnownHost) {
        entries += host
    }
}
