package app.skerry.shared.rdp

import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(store.verify(offer("desk", "aa")))

        assertFalse(store.verify(offer("desk", "bb")), "a changed certificate was accepted")
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
                store.verify(offer(host, "fp-$host"))
            }
        }.forEach { it.join() }

        val remembered = store.entries()
        assertEquals(
            hosts.map { "$it:3389" }.toSet(),
            remembered.keys,
            "a fingerprint recorded on first sight was lost to a concurrent one",
        )
    }
}
