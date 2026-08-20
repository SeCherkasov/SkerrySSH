package app.skerry.shared.rdp

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import kotlinx.coroutines.withContext

/**
 * Drives the whole stack against a real RDP server, which is the only way to test the parts a
 * hand-written fixture cannot reach: what a server actually puts in its capability sets, how it
 * paces frames, and whether the logon is accepted.
 *
 * Skipped unless a server is listening, so it never fails a normal build. To run it:
 *
 * ```
 * docker run -d --name skerry-xrdp -p 33890:3389 skerry-xrdp:test
 * SKERRY_RDP_HOST=127.0.0.1 SKERRY_RDP_PORT=33890 \
 *   SKERRY_RDP_USER=rdpuser SKERRY_RDP_PASSWORD=rdppass123 ./gradlew :shared:desktopTest --tests '*RdpLiveServerTest*'
 * ```
 */
class RdpLiveServerTest {

    private val host = System.getenv("SKERRY_RDP_HOST") ?: "127.0.0.1"
    private val port = System.getenv("SKERRY_RDP_PORT")?.toIntOrNull() ?: 33890
    private val user = System.getenv("SKERRY_RDP_USER") ?: "rdpuser"
    private val password = System.getenv("SKERRY_RDP_PASSWORD") ?: "rdppass123"
    private val domain = System.getenv("SKERRY_RDP_DOMAIN") ?: ""

    @Test
    fun `connects, logs on and receives a first frame`() = runBlocking {
        // Reported as skipped, not passed: a green run of a test that asserted nothing reads in CI
        // exactly like a green run of one that did.
        Assumptions.assumeTrue(serverIsListening(), "nothing listening on $host:$port")

        val fingerprints = mutableListOf<String>()
        // Trusts whatever the test server presents, and records nothing anywhere.
        val transport = RdpTcpTransport(
            certificateVerifier = RecordingVerifier(onVerify = { fingerprints += it.fingerprintSha256 }),
        )

        val session = transport.connect(
            RdpTarget(host = host, port = port, desktopWidth = 1024, desktopHeight = 768),
            RdpCredentials(username = user, password = password, domain = domain),
        )

        val firstFrame = CompletableFuture<RdpUpdate>()
        val collector = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                session.updates.collect { update ->
                    when {
                        update is RdpUpdate.Region && update.rects.isNotEmpty() -> firstFrame.complete(update)
                        update is RdpUpdate.Closed -> firstFrame.completeExceptionally(
                            IllegalStateException("session closed: ${update.reason}"),
                        )

                        else -> Unit
                    }
                }
            }.onFailure { firstFrame.completeExceptionally(it) }
        }

        try {
            val update = withContext(Dispatchers.IO) { firstFrame.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            println("first frame: ${(update as RdpUpdate.Region).rects.take(3)}")
            println("desktop: ${session.desktopWidth}x${session.desktopHeight}")
            println("certificate: ${fingerprints.firstOrNull()}")
            assertTrue(session.desktopWidth > 0 && session.desktopHeight > 0)
            // Something was actually painted, rather than a frame of untouched black.
            assertTrue(session.framebuffer.pixels.any { it != 0 }, "the framebuffer stayed empty")
        } finally {
            collector.cancel()
            session.close()
        }
    }

    private fun serverIsListening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }
    }.isSuccess

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
