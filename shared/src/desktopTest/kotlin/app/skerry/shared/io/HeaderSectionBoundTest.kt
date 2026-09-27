package app.skerry.shared.io

import app.skerry.shared.ai.OpenAiProvider
import app.skerry.shared.ai.local.ModelDownloader
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.update.GithubReleaseClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every server the app talks to over HTTP is one it does not control: the sync server is often
 * somebody else's box, the AI endpoint is user-typed, a model URL is whatever a catalog says. A
 * response whose header section never ends must be cut off after a bounded amount of it, not read
 * until the process runs out of memory — CIO bounded each header line but not how many there were,
 * and on a WebSocket upgrade it waited for them with no timeout at all.
 */
class HeaderSectionBoundTest {

    private val session = SyncSession(accountId = "a@example.com", accessToken = "t", refreshToken = "r")

    /** Runs [call] against [peer] and fails unless it gave up early, with the flood far from done. */
    private fun assertCutOff(peer: HostilePeer, call: suspend () -> Unit) = peer.use {
        val failure = runBlocking {
            runCatching { withTimeout(CLIENT_BUDGET_MS) { call() } }.exceptionOrNull()
        }
        assertNotNull(failure, "an unfinished header section was accepted as a response")
        if (failure is TimeoutCancellationException) {
            fail("the client was still reading headers after ${CLIENT_BUDGET_MS / 1000} s (${peer.sent.get()} bytes)")
        }
        val sent = peer.settle()
        assertTrue(sent < MAX_READ_BYTES, "the client took $sent bytes of headers before giving up")
    }

    private fun HttpClient.probe(peer: HostilePeer) = assertCutOff(peer) { use { it.get(peer.httpUrl) } }

    @Test
    fun `a sync call gives up on a header section that never ends`() {
        val peer = HostilePeer.headerFlood("HTTP/1.1 200 OK")
        assertCutOff(peer) { KtorSyncClient(peer.httpUrl).pull(session, since = 0) }
    }

    @Test
    fun `the sync socket gives up on an upgrade whose header section never ends`() {
        val peer = HostilePeer.headerFlood("HTTP/1.1 101 Switching Protocols")
        assertCutOff(peer) { KtorSyncClient(peer.httpUrl).changes(session).collect() }
    }

    @Test
    fun `a share relay socket gives up on an upgrade whose header section never ends`() {
        val peer = HostilePeer.headerFlood("HTTP/1.1 101 Switching Protocols")
        assertCutOff(peer) { KtorSyncClient(peer.httpUrl).joinShare(session, "team-e2e", "share-e2e") {} }
    }

    @Test
    fun `the AI clients give up on a header section that never ends`() {
        OpenAiProvider.defaultHttpClient().probe(HostilePeer.headerFlood("HTTP/1.1 200 OK"))
        OpenAiProvider.catalogHttpClient().probe(HostilePeer.headerFlood("HTTP/1.1 200 OK"))
    }

    @Test
    fun `the update check gives up on a header section that never ends`() {
        GithubReleaseClient.defaultHttpClient().probe(HostilePeer.headerFlood("HTTP/1.1 200 OK"))
    }

    @Test
    fun `a model download gives up on a header section that never ends`() {
        ModelDownloader.defaultHttpClient().probe(HostilePeer.headerFlood("HTTP/1.1 200 OK"))
    }

    private companion object {
        /** Far longer than a bounded client needs, shorter than CIO's 15 s request timeout. */
        const val CLIENT_BUDGET_MS = 10_000L

        /** A bounded client stops within a few hundred KiB; socket buffers add a few MiB on top. */
        const val MAX_READ_BYTES = 24L * 1024 * 1024
    }
}
