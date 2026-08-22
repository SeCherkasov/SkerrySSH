package app.skerry.shared.ssh

import app.skerry.shared.trust.HostTrustDecider
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.shared.trust.RecordingHostTrust
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TofuHostKeyVerifierTest {

    private val ed25519 = "ssh-ed25519"
    private val fpA = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val fpB = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    private val fpC = "SHA256:CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"

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
    fun `names a changed key as such, so the UI can point at the known-hosts manager`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB))
    }

    @Test
    fun `an unreadable store is not reported as a changed key`() {
        // "Unlock the vault and retry" and "the host's key changed" are opposite conclusions; a
        // locked vault must not read as the MITM signal.
        val store = InMemoryKnownHostsStore().apply { readable = false }
        val verifier = TofuHostKeyVerifier(store)

        assertEquals(HostKeyRefusal.TrustStoreUnreadable, verifier.check("example.com", 22, ed25519, fpA))
    }

    @Test
    fun `an accepted key has no refusal`() {
        val verifier = TofuHostKeyVerifier(InMemoryKnownHostsStore())

        assertNull(verifier.check("example.com", 22, ed25519, fpA))
    }

    @Test
    fun `asks the user before trusting a host on first contact`() {
        val store = InMemoryKnownHostsStore()
        val asked = RecordingHostTrust(answer = false)
        val verifier = TofuHostKeyVerifier(store, trust = asked)

        assertEquals(HostKeyRefusal.RejectedByUser, verifier.check("example.com", 22, ed25519, fpA))

        val request = asked.requests.single()
        assertEquals(HostTrustKind.SshHostKey, request.kind)
        assertEquals("example.com", request.host)
        assertEquals(22, request.port)
        assertEquals(ed25519, request.keyType)
        assertEquals(fpA, request.fingerprint)
        assertNull(request.recordedFingerprint, "a first contact has nothing recorded to compare against")
        assertEquals(emptyList(), store.all(), "a key the user turned down must not be remembered")
    }

    @Test
    fun `a key the user accepts on first contact is trusted and remembered`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, trust = RecordingHostTrust(answer = true))

        assertNull(verifier.check("example.com", 22, ed25519, fpA))
        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
    }

    @Test
    fun `a known key is trusted without asking again`() {
        val store = InMemoryKnownHostsStore()
        val asked = RecordingHostTrust(answer = true)
        val verifier = TofuHostKeyVerifier(store, trust = asked)
        verifier.verify("example.com", 22, ed25519, fpA)

        assertNull(verifier.check("example.com", 22, ed25519, fpA))
        assertEquals(1, asked.requests.size, "an unchanged key must not put a dialog in the way")
    }

    @Test
    fun `a changed key is shown with the fingerprint it replaces`() {
        val store = InMemoryKnownHostsStore()
        val asked = RecordingHostTrust(answer = false)
        val verifier = TofuHostKeyVerifier(store, trust = asked)
        store.add(KnownHost("example.com", 22, ed25519, fpA))

        assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB))

        val request = asked.requests.single()
        assertEquals(fpA, request.recordedFingerprint)
        assertEquals(fpB, request.fingerprint)
        assertTrue(request.keyChanged)
    }

    @Test
    fun `a changed key the user accepts replaces the trusted one and clears the mismatch`() {
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val verifier = TofuHostKeyVerifier(
            store,
            mismatches,
            now = { "2026-08-22T12:00:00Z" },
            trust = RecordingHostTrust(answer = true),
        )
        store.add(KnownHost("example.com", 22, ed25519, fpA))
        mismatches.record(HostKeyMismatch("example.com", 22, ed25519, fpA, fpB))

        assertNull(verifier.check("example.com", 22, ed25519, fpB))

        assertEquals(
            listOf(KnownHost("example.com", 22, ed25519, fpB, "2026-08-22T12:00:00Z")),
            store.all(),
            "accepting the new key must replace the old record, not add a second one",
        )
        assertEquals(emptyList(), mismatches.all(), "a resolved key change must not stay in the warning list")
    }

    @Test
    fun `a changed key the user refuses is recorded as a mismatch and stays untrusted`() {
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val verifier = TofuHostKeyVerifier(store, mismatches, trust = RecordingHostTrust(answer = false))
        store.add(KnownHost("example.com", 22, ed25519, fpA))

        assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB))

        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
        assertEquals(1, mismatches.all().size, "the known-hosts manager still needs the event on record")
    }

    @Test
    fun `nobody is asked while the store is unreadable`() {
        // Fail closed without a dialog: the trusted set is unknown, so the question would be
        // "trust this key?" with no way to say what it replaces — and a locked vault has no UI.
        val store = InMemoryKnownHostsStore().apply { readable = false }
        val asked = RecordingHostTrust(answer = true)
        val verifier = TofuHostKeyVerifier(store, trust = asked)

        assertEquals(HostKeyRefusal.TrustStoreUnreadable, verifier.check("example.com", 22, ed25519, fpA))
        assertEquals(emptyList(), asked.requests)
    }

    @Test
    fun `without a decider the first key is trusted silently and a changed one is not`() {
        // The default is what every release before the dialog did — a graph assembled without a UI
        // must not start accepting changed keys just because nobody passed a decider.
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store)

        assertNull(verifier.check("example.com", 22, ed25519, fpA))
        assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB))
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

    @Test
    fun `a key that landed while the question was on screen is not overwritten by the answer`() {
        // The dialog holds the handshake open for as long as a person takes to read a fingerprint.
        // A second connection to the same host can record a key inside that window, and the answer
        // was given to a question that said "new host key" — it says nothing about replacing what
        // is there now, and committing it blind would swap a trusted key with no warning shown.
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val landing = HostTrustDecider {
            store.add(KnownHost("example.com", 22, ed25519, fpA))
            true
        }
        val verifier = TofuHostKeyVerifier(store, mismatches, trust = landing)

        assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB))

        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
        assertEquals(1, mismatches.all().size, "the key that was turned away belongs in the warning list")
    }

    @Test
    fun `a replacement is refused when the record it was weighed against moved meanwhile`() {
        val store = InMemoryKnownHostsStore()
        val moving = HostTrustDecider {
            store.replace(KnownHost("example.com", 22, ed25519, fpC))
            true
        }
        val verifier = TofuHostKeyVerifier(store, trust = moving)
        store.add(KnownHost("example.com", 22, ed25519, fpA))

        assertEquals(HostKeyRefusal.KeyChanged, verifier.check("example.com", 22, ed25519, fpB))
        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpC)), store.all())
    }

    @Test
    fun `a record forgotten while the question was on screen is refused, but not as a key change`() {
        // The user answered the dialog in one window and forgot the host in another. Nothing was
        // replaced and nothing is trusted, so calling it a key change would send them to a
        // known-hosts panel holding neither the key nor a warning to compare it against.
        val store = InMemoryKnownHostsStore()
        val mismatches = InMemoryHostKeyMismatchStore()
        val forgetting = HostTrustDecider {
            store.remove("example.com", 22, ed25519)
            true
        }
        val verifier = TofuHostKeyVerifier(store, mismatches, trust = forgetting)
        store.add(KnownHost("example.com", 22, ed25519, fpA))

        assertEquals(HostKeyRefusal.RejectedByUser, verifier.check("example.com", 22, ed25519, fpB))
        assertEquals(emptyList(), store.all(), "a key was trusted against a record that had gone")
        assertEquals(emptyList(), mismatches.all(), "there was no key to warn about")
    }

    @Test
    fun `a key type the host has no record of is put to the user as a host already known`() {
        // The server picks which host-key algorithm the exchange uses. Asked as a plain first
        // contact, an interception only has to offer a type this store has never seen to turn "this
        // key changed" into "never seen this host" — the question a user says yes to.
        val store = InMemoryKnownHostsStore()
        val asked = RecordingHostTrust(answer = false)
        val verifier = TofuHostKeyVerifier(store, trust = asked)
        store.add(KnownHost("example.com", 22, ed25519, fpA))
        store.add(KnownHost("example.com", 22, "rsa-sha2-512", fpC))

        assertEquals(HostKeyRefusal.RejectedByUser, verifier.check("example.com", 22, "ecdsa-sha2-nistp256", fpB))

        val request = asked.requests.single()
        assertFalse(request.keyChanged, "nothing was replaced — there is no fingerprint to show against")
        assertTrue(request.hostAlreadyKnown, "a host with keys on file was asked about as a first contact")
        assertEquals(listOf("rsa-sha2-512", ed25519), request.recordedKeyTypes)
    }

    @Test
    fun `a key type recorded for another host is not counted against this one`() {
        val store = InMemoryKnownHostsStore()
        val asked = RecordingHostTrust(answer = false)
        val verifier = TofuHostKeyVerifier(store, trust = asked)
        store.add(KnownHost("other.example.com", 22, ed25519, fpA))
        store.add(KnownHost("example.com", 2222, ed25519, fpC))

        verifier.check("example.com", 22, "rsa-sha2-512", fpB)

        assertEquals(emptyList(), asked.requests.single().recordedKeyTypes)
    }

    @Test
    fun `the same key landing meanwhile is not a conflict`() {
        // Two connections to the same unknown host, one answer each, and both saw the same key.
        val store = InMemoryKnownHostsStore()
        val landing = HostTrustDecider {
            store.add(KnownHost("example.com", 22, ed25519, fpA))
            true
        }
        val verifier = TofuHostKeyVerifier(store, trust = landing)

        assertNull(verifier.check("example.com", 22, ed25519, fpA))
        assertEquals(listOf(KnownHost("example.com", 22, ed25519, fpA)), store.all())
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
