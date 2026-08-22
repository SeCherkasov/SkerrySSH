package app.skerry.shared.rdp

import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.shared.trust.RecordingHostTrust
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** The trust-on-first-use store: what it remembers has to survive connections made at the same time. */
class FileRdpCertificateStoreTest {

    private val directory = kotlin.io.path.createTempDirectory("rdp-certs").toFile()
    private val file = directory.resolve("known_certificates").toOkioPath()
    private val store = FileRdpCertificateStore(file, FileSystem.SYSTEM)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** The state a host is already remembered in, recorded the way a real first connection does. */
    private fun seed(host: String, fingerprint: String) {
        val trusting = FileRdpCertificateStore(file, FileSystem.SYSTEM)
        check(trusting.verify(offer(host, fingerprint)) && trusting.remember(offer(host, fingerprint)))
    }

    private fun offer(host: String, fingerprint: String) = RdpCertificateOffer(
        host = host,
        port = 3389,
        fingerprintSha256 = fingerprint,
        subject = "CN=$host",
        issuer = "CN=$host",
        notBeforeMillis = 0,
        notAfterMillis = 0,
        trustedByPlatform = false,
        hostnameMatches = true,
        publicKey = ByteArray(0),
        derChain = emptyList(),
    )

    @Test
    fun `a fingerprint seen once is refused when it changes`() {
        assertTrue(store.verify(offer("desk", "aa")))
        store.remember(offer("desk", "aa"))
        assertTrue(store.verify(offer("desk", "aa")))

        assertFalse(store.verify(offer("desk", "bb")), "a changed certificate was accepted")
    }

    @Test
    fun `a certificate is only remembered once the connection it came from succeeded`() {
        // verify() is asked from inside the TLS handshake, before the server has proven it holds
        // the private key. A peer that presents a certificate lifted from somewhere else and then
        // walks away must not get to write the entry every later connection is judged against.
        assertTrue(store.verify(offer("desk", "aa")))

        assertEquals(emptyMap(), store.entries(), "an unproven certificate was recorded")

        store.remember(offer("desk", "aa"))
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
    }

    @Test
    fun `of two first connections racing, the one the host is not known by is refused`() {
        // Both pass verify() against an empty store — the entry is written only once each
        // handshake finishes, and between the two the certificate can differ.
        val first = offer("desk", "aa")
        val second = offer("desk", "bb")
        assertTrue(store.verify(first))
        assertTrue(store.verify(second))

        assertTrue(store.remember(first))
        assertFalse(store.remember(second), "a second certificate was accepted for the same host")
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
    }

    @Test
    fun `first connections made at the same time all leave their fingerprint behind`() {
        // Read-then-write without a lock loses whichever host wrote first: the entry is gone at the
        // next connection, and that host is trusted on first use a second time — which is exactly
        // the moment a swapped certificate would have been caught.
        val hosts = (1..16).map { "host-$it" }
        val start = CyclicBarrier(hosts.size)
        hosts.map { host ->
            thread {
                start.await()
                val candidate = offer(host, "fp-$host")
                if (store.verify(candidate)) store.remember(candidate)
            }
        }.forEach { it.join() }

        val remembered = store.entries()
        assertEquals(
            hosts.map { "$it:3389" }.toSet(),
            remembered.keys,
            "a fingerprint recorded on first sight was lost to a concurrent one",
        )
    }

    @Test
    fun `asks the user before a certificate is trusted on first sight`() {
        val asked = RecordingHostTrust(answer = false)
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = asked)

        assertFalse(store.verify(offer("desk", "aa")), "a certificate the user turned down was accepted")

        val request = asked.requests.single()
        assertEquals(HostTrustKind.RdpCertificate, request.kind)
        assertEquals("desk", request.host)
        assertEquals(3389, request.port)
        assertEquals("aa", request.fingerprint)
        assertNull(request.recordedFingerprint)
        assertEquals("CN=desk", request.certificate?.subject)
        assertEquals(emptyMap(), store.entries())
    }

    @Test
    fun `a certificate the user accepts is remembered once the handshake finishes`() {
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = true))

        assertTrue(store.verify(offer("desk", "aa")))
        assertTrue(store.remember(offer("desk", "aa")))
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
    }

    @Test
    fun `a known certificate is trusted without asking again`() {
        val asked = RecordingHostTrust(answer = true)
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = asked)
        store.verify(offer("desk", "aa"))
        store.remember(offer("desk", "aa"))

        assertTrue(store.verify(offer("desk", "aa")))
        assertEquals(1, asked.requests.size, "an unchanged certificate must not put a dialog in the way")
    }

    @Test
    fun `a changed certificate the user accepts replaces the remembered one`() {
        val asked = RecordingHostTrust(answer = true)
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = asked)
        store.verify(offer("desk", "aa"))
        store.remember(offer("desk", "aa"))

        assertTrue(store.verify(offer("desk", "bb")))
        assertEquals("aa", asked.requests.last().recordedFingerprint, "the dialog must show what is being replaced")
        // Still only committed once the handshake proves the server holds the key.
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
        assertTrue(store.remember(offer("desk", "bb")))
        assertEquals(mapOf("desk:3389" to "bb"), store.entries())
    }

    @Test
    fun `a changed certificate the user refuses is neither accepted nor remembered`() {
        seed("desk", "aa")
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = false))

        assertFalse(store.verify(offer("desk", "bb")))
        assertFalse(store.remember(offer("desk", "bb")), "a refused certificate must not be committed")
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
    }

    @Test
    fun `approving a replacement covers only the certificate that was shown`() {
        // The user answered for the fingerprint in the dialog. A different one arriving before the
        // handshake finishes is a second server, not the one that was approved.
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = true))
        store.verify(offer("desk", "aa"))
        store.remember(offer("desk", "aa"))
        assertTrue(store.verify(offer("desk", "bb")))

        assertFalse(store.remember(offer("desk", "cc")), "an unapproved certificate overwrote the record")
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
    }

    @Test
    fun `a second approval does not take the first one's place, and only one of them commits`() {
        // One slot per host loses whichever answer came first: the user accepts a rotated
        // certificate in two tabs, and the tab that asked first is told its certificate was
        // rejected — for a dialog it watched the user accept. Only one of the two can win the
        // store, though: once `bb` is written, the answer given about replacing `aa` says nothing
        // about replacing `bb`, and the second tab is refused.
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = true))
        store.verify(offer("desk", "aa"))
        store.remember(offer("desk", "aa"))

        assertTrue(store.verify(offer("desk", "bb")))
        assertTrue(store.verify(offer("desk", "cc")))

        assertTrue(store.remember(offer("desk", "bb")), "an approved replacement was refused")
        assertEquals(mapOf("desk:3389" to "bb"), store.entries())
        assertFalse(store.remember(offer("desk", "cc")), "an answer about replacing aa also replaced bb")
        assertEquals(mapOf("desk:3389" to "bb"), store.entries())
    }

    @Test
    fun `a host cannot pile up approvals without bound, and the newest still commits`() {
        // Every verify() a host is refused on leaves an approval nothing ever collects — a server
        // that hangs up after the handshake can repeat that for as long as the app runs. The set is
        // capped and drops the oldest, which is the answer least likely to still have a connection
        // behind it; what the user just accepted has to survive.
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = true))
        val overflowing = (1..MAX_APPROVALS_PER_HOST + 1).map { "fp-$it" }
        overflowing.forEach { assertTrue(store.verify(offer("desk", it))) }

        assertFalse(store.remember(offer("desk", overflowing.first())), "an evicted approval still committed")
        assertTrue(store.remember(offer("desk", overflowing.last())), "the newest approval was dropped")
        assertEquals(mapOf("desk:3389" to overflowing.last()), store.entries())
    }

    @Test
    fun `an approval given while the host was unknown does not replace what landed meanwhile`() {
        // Both answers were "trust this host, which I have never seen". Neither of them was an
        // answer about replacing the fingerprint the other one wrote.
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = true))
        assertTrue(store.verify(offer("desk", "aa")))
        assertTrue(store.verify(offer("desk", "bb")))

        assertTrue(store.remember(offer("desk", "aa")))
        assertFalse(store.remember(offer("desk", "bb")), "a first-contact answer overwrote a recorded key")
        assertEquals(mapOf("desk:3389" to "aa"), store.entries())
    }

    @Test
    fun `a certificate never put to the user is not committed`() {
        // remember() is the commit, and it takes only what verify() showed someone. Nothing in the
        // connector reaches it another way today; the invariant belongs to the store all the same.
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = RecordingHostTrust(answer = true))

        assertFalse(store.remember(offer("desk", "aa")), "a certificate nobody vouched for was recorded")
        assertEquals(emptyMap(), store.entries())
    }

    @Test
    fun `a host the line format cannot hold is refused rather than written`() {
        // An RDP server names its own host through the redirection PDU. One entry per line and the
        // key up to the first space, so a newline would write a line that reads back as an entry
        // for another host — pre-approving the attacker's certificate for it.
        val asked = RecordingHostTrust(answer = true)
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = asked)

        assertFalse(store.verify(offer("evil\nprod-server", "aa")), "a host with a newline was accepted")
        assertFalse(store.remember(offer("evil\nprod-server", "aa")))
        assertEquals(emptyMap(), store.entries())
        assertEquals(0, asked.requests.size, "a host that cannot be recorded must not be put to the user")
    }

    @Test
    fun `an unreadable store refuses rather than trusting on first use`() {
        // A store that cannot be read is not an empty one: treating it as "host never seen" is
        // exactly the moment a swapped certificate would go through unnoticed.
        // Assumes the test does not run as root, for whom the read bit means nothing.
        file.toFile().writeText("desk:3389 aa\n")
        file.toFile().setReadable(false)
        val asked = RecordingHostTrust(answer = true)
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = asked)

        assertFailsWith<IOException>("an unreadable store read as empty") { store.verify(offer("desk", "bb")) }
        assertEquals(0, asked.requests.size, "the user was asked against a trusted set nobody could read")
    }

    @Test
    fun `a host the reader would skip as a comment is refused rather than written`() {
        // read() drops a line starting with '#', so writing one records nothing: the host would be
        // a first contact on every connect and a swapped certificate would never read as a change.
        // An RDP broker names the redirect target itself, so the string is the server's to choose.
        val asked = RecordingHostTrust(answer = true)
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM, trust = asked)

        assertFalse(store.verify(offer("#rds-01", "aa")), "a host the store cannot hold was accepted")
        assertFalse(store.remember(offer("#rds-01", "aa")))
        assertEquals(emptyMap(), store.entries())
        assertEquals(0, asked.requests.size, "a host that cannot be recorded must not be put to the user")
    }

    @Test
    fun `without a decider a first certificate is trusted silently and a changed one is not`() {
        val store = FileRdpCertificateStore(file, FileSystem.SYSTEM)

        assertTrue(store.verify(offer("desk", "aa")))
        store.remember(offer("desk", "aa"))
        assertFalse(store.verify(offer("desk", "bb")))
    }

}
