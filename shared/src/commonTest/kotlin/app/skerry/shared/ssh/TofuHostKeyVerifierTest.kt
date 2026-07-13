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
        // Does not duplicate an already known entry.
        assertEquals(1, store.all().size)
    }

    @Test
    fun `rejects a changed fingerprint for a known host key`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertFalse(verifier.verify("example.com", 22, ed25519, fpB))
        // A rejection does not overwrite the trusted key.
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

    @Test
    fun `stamps firstSeen on the trusted key from the clock`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, now = { "2026-06-22T10:00:00Z" })

        verifier.verify("example.com", 22, ed25519, fpA)

        assertEquals("2026-06-22T10:00:00Z", store.all().single().firstSeen)
    }

    @Test
    fun `records a mismatch event when a known key changes`() {
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val verifier = TofuHostKeyVerifier(store, mismatches, now = { "2026-06-22T11:00:00Z" })
        verifier.verify("example.com", 22, ed25519, fpA)

        assertFalse(verifier.verify("example.com", 22, ed25519, fpB))

        assertEquals(
            listOf(HostKeyMismatch("example.com", 22, ed25519, fpA, fpB, "2026-06-22T11:00:00Z")),
            mismatches.all(),
        )
    }

    @Test
    fun `does not record a mismatch when the key matches`() {
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val verifier = TofuHostKeyVerifier(store, mismatches)
        verifier.verify("example.com", 22, ed25519, fpA)

        verifier.verify("example.com", 22, ed25519, fpA)

        assertEquals(emptyList(), mismatches.all())
    }

    @Test
    fun `rejects any key while the store is unreadable instead of TOFU-accepting`() {
        // Locked-vault race: an unreadable store must not look like "host never seen" — a MITM
        // key would be silently trusted. Fail closed.
        val store = InMemoryKnownHostsStore().apply { readable = false }
        val verifier = TofuHostKeyVerifier(store)

        assertFalse(verifier.verify("example.com", 22, ed25519, fpA))
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `rejects a known host too while the store is unreadable and records no mismatch`() {
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val verifier = TofuHostKeyVerifier(store, mismatches)
        verifier.verify("example.com", 22, ed25519, fpA)
        store.readable = false

        assertFalse(verifier.verify("example.com", 22, ed25519, fpA))
        // The trusted fingerprint is unknown while unreadable — nothing meaningful to record.
        assertEquals(emptyList(), mismatches.all())
    }
}

/** In-memory store for TOFU logic tests. [readable] = false models a locked vault. */
private class InMemoryKnownHostsStore : KnownHostsStore {
    private val entries = mutableListOf<KnownHost>()
    var readable = true
    override fun allOrNull(): List<KnownHost>? = if (readable) all() else null
    override fun all(): List<KnownHost> = entries.toList()
    override fun add(host: KnownHost) {
        entries += host
    }

    override fun replace(host: KnownHost) {
        entries.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
        entries += host
    }

    override fun remove(host: String, port: Int, keyType: String) {
        entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
    }
}

/** In-memory key-change log for tests. */
private class InMemoryHostKeyMismatchStore : HostKeyMismatchStore {
    private val entries = mutableListOf<HostKeyMismatch>()
    override fun all(): List<HostKeyMismatch> = entries.toList()
    override fun record(mismatch: HostKeyMismatch) {
        entries.removeAll { it.host == mismatch.host && it.port == mismatch.port && it.keyType == mismatch.keyType }
        entries += mismatch
    }

    override fun clear(host: String, port: Int, keyType: String) {
        entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
    }
}
