package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyHostKeyVerifierTest {

    private val ed25519 = "ssh-ed25519"
    private val fpA = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val fpB = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    /** The policy only decides a host with no entry; everything else has to answer the same either way. */
    private fun bothPolicies(check: (UnknownHost, RecordingKnownHostsStore) -> Unit) {
        for (policy in UnknownHost.entries) {
            check(policy, RecordingKnownHostsStore())
        }
    }

    @Test
    fun `a matching stored key is accepted under either policy, without writing`() {
        bothPolicies { policy, store ->
            store.seed(KnownHost("example.com", 22, ed25519, fpA))
            val verifier = ReadOnlyHostKeyVerifier(store, policy)

            assertTrue(verifier.verify("example.com", 22, ed25519, fpA), "$policy")
            assertEquals(0, store.adds, "$policy")
        }
    }

    @Test
    fun `a changed key for a known host is rejected under either policy, store left intact`() {
        bothPolicies { policy, store ->
            store.seed(KnownHost("example.com", 22, ed25519, fpA))
            val verifier = ReadOnlyHostKeyVerifier(store, policy)

            assertFalse(verifier.verify("example.com", 22, ed25519, fpB), "$policy")
            assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all(), "$policy")
            assertEquals(0, store.adds, "$policy")
        }
    }

    @Test
    fun `an unreadable store rejects everything under either policy`() {
        // Same fail-closed rule as TOFU: a locked vault must not read as "host never seen", which
        // under Accept would otherwise wave every key through.
        bothPolicies { policy, store ->
            store.seed(KnownHost("example.com", 22, ed25519, fpA))
            store.readable = false
            val verifier = ReadOnlyHostKeyVerifier(store, policy)

            assertFalse(verifier.verify("example.com", 22, ed25519, fpA), "$policy")
            assertFalse(verifier.verify("other.example.com", 22, ed25519, fpB), "$policy")
        }
    }

    @Test
    fun `Accept lets an unknown host through and still writes nothing`() {
        // "Test connection" from the form: the host is usually not saved yet, and the user is reading
        // the answer. Accepting must not leave a trace or establish trust.
        val store = RecordingKnownHostsStore()
        val verifier = ReadOnlyHostKeyVerifier(store, UnknownHost.Accept)

        assertTrue(verifier.verify("example.com", 22, ed25519, fpA))

        assertEquals(emptyList(), store.all())
        assertEquals(0, store.adds)
    }

    @Test
    fun `Refuse turns an unknown host away`() {
        // Activating a saved tunnel: no terminal, no prompt, nobody watching. This connection must not
        // be the one that settles what key the host has.
        val store = RecordingKnownHostsStore()
        val verifier = ReadOnlyHostKeyVerifier(store, UnknownHost.Refuse)

        assertFalse(verifier.verify("example.com", 22, ed25519, fpA))
        assertEquals(0, store.adds)
    }

    @Test
    fun `Refuse says the host is merely untrusted, not that its key changed`() {
        // This is the one refusal the user can fix themselves — open a terminal session once, or
        // add a CA — and the message can only say so if the reason survives the trip to the UI.
        val store = RecordingKnownHostsStore()
        val verifier = ReadOnlyHostKeyVerifier(store, UnknownHost.Refuse)

        assertEquals(HostKeyRefusal.NotTrustedYet, verifier.check("example.com", 22, ed25519, fpA))
    }

    @Test
    fun `a changed key is named as such under either policy`() {
        bothPolicies { policy, store ->
            store.seed(KnownHost("example.com", 22, ed25519, fpA))
            val verifier = ReadOnlyHostKeyVerifier(store, policy)

            assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB), "$policy")
        }
    }

    @Test
    fun `an unreadable store is named as such under either policy`() {
        bothPolicies { policy, store ->
            store.readable = false
            val verifier = ReadOnlyHostKeyVerifier(store, policy)

            assertEquals(
                HostKeyRefusal.TrustStoreUnreadable,
                verifier.check("example.com", 22, ed25519, fpA),
                "$policy",
            )
        }
    }

    @Test
    fun `a new key type for a known host counts as an unknown host`() {
        // ed25519 is stored, the server offers rsa: a different (host, port, keyType) triple, so there
        // is no key to compare against and the policy decides.
        val seeded = { store: RecordingKnownHostsStore -> store.seed(KnownHost("example.com", 22, ed25519, fpA)) }

        val accepting = RecordingKnownHostsStore().also(seeded)
        assertTrue(ReadOnlyHostKeyVerifier(accepting, UnknownHost.Accept).verify("example.com", 22, "rsa-sha2-512", fpB))
        assertEquals(0, accepting.adds)

        val refusing = RecordingKnownHostsStore().also(seeded)
        assertFalse(ReadOnlyHostKeyVerifier(refusing, UnknownHost.Refuse).verify("example.com", 22, "rsa-sha2-512", fpB))
    }
}

/** In-memory known-hosts store that counts [add] calls, to verify the read-only behaviour. */
private class RecordingKnownHostsStore : KnownHostsStore {
    private val entries = mutableListOf<KnownHost>()
    var adds = 0
        private set
    var readable = true

    /** Seeds an entry without incrementing [adds] (pre-fills trusted state). */
    fun seed(host: KnownHost) { entries += host }

    override fun allOrNull(): List<KnownHost>? = if (readable) all() else null
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
