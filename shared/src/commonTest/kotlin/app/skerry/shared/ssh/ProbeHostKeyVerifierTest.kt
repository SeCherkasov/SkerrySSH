package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeHostKeyVerifierTest {

    private val ed25519 = "ssh-ed25519"
    private val fpA = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val fpB = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun `accepts an unknown host without writing to the store`() {
        val store = RecordingKnownHostsStore()
        val verifier = ProbeHostKeyVerifier(store)

        assertTrue(verifier.verify("example.com", 22, ed25519, fpA))

        // Probing leaves no trace in known_hosts.
        assertEquals(emptyList(), store.all())
        assertEquals(0, store.adds)
    }

    @Test
    fun `accepts a matching trusted key without writing`() {
        val store = RecordingKnownHostsStore().apply { seed(KnownHost("example.com", 22, ed25519, fpA)) }
        val verifier = ProbeHostKeyVerifier(store)

        assertTrue(verifier.verify("example.com", 22, ed25519, fpA))
        assertEquals(0, store.adds)
    }

    @Test
    fun `accepts a new key type for a known host without writing`() {
        // ed25519 is known, server presents rsa: a new (host, port, keyType) triple, so the probe
        // accepts it as a new host and does not write.
        val store = RecordingKnownHostsStore().apply { seed(KnownHost("example.com", 22, ed25519, fpA)) }
        val verifier = ProbeHostKeyVerifier(store)

        assertTrue(verifier.verify("example.com", 22, "rsa-sha2-512", fpB))
        assertEquals(0, store.adds)
    }

    @Test
    fun `rejects a changed key for a known host and leaves the store intact`() {
        val store = RecordingKnownHostsStore().apply { seed(KnownHost("example.com", 22, ed25519, fpA)) }
        val verifier = ProbeHostKeyVerifier(store)

        assertFalse(verifier.verify("example.com", 22, ed25519, fpB))
        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
        assertEquals(0, store.adds)
    }
}

/** In-memory known-hosts store that counts [add] calls, to verify probe read-only behavior. */
private class RecordingKnownHostsStore : KnownHostsStore {
    private val entries = mutableListOf<KnownHost>()
    var adds = 0
        private set

    /** Seeds an entry without incrementing [adds] (pre-fills trusted state). */
    fun seed(host: KnownHost) { entries += host }

    override fun all(): List<KnownHost> = entries.toList()
    override fun add(host: KnownHost) { adds++; entries += host }
    override fun replace(host: KnownHost) {
        entries.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
        entries += host
    }
    override fun remove(host: String, port: Int, keyType: String) {
        entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
    }
}
