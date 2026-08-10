package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.SharesResponse
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamInviteRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Relay routes for shared sessions. The frames themselves are opaque here on purpose — the server
 * never holds the team key, so these tests cover routing, authorization and lifetime.
 */
class ShareRoutesTest {

    private val teamId = "team-share"
    private val pw = "auth-key-hex"
    private val meta = "session label".encodeToByteArray().b64()

    private fun ApplicationTestBuilder.wsClient() = createClient {
        install(ContentNegotiation) { json() }
        install(WebSockets)
    }

    private suspend fun HttpClient.createTeam(token: String) = post("/teams") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(TeamCreateRequest(teamId))
    }

    private suspend fun HttpClient.invite(token: String, target: String, role: String = "editor") =
        post("/teams/$teamId/members") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamInviteRequest(target, byteArrayOf(1, 2, 3).b64(), role))
        }

    private suspend fun HttpClient.accept(token: String) = post("/teams/$teamId/accept") { bearerAuth(token) }

    private suspend fun HttpClient.shares(token: String) = get("/teams/$teamId/shares") { bearerAuth(token) }

    private fun hostPath(shareId: String, meta: String) = "/teams/$teamId/shares/$shareId/host?meta=$meta"
    private fun joinPath(shareId: String) = "/teams/$teamId/shares/$shareId/join"

    /** Owner + one accepted member, the setup every relay test starts from. */
    private suspend fun HttpClient.teamOfTwo(): Pair<String, String> {
        val owner = registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val mate = registerAccount("mate@x.io", pw, deviceId = "d-mate")
        createTeam(owner.accessToken)
        invite(owner.accessToken, "mate@x.io")
        accept(mate.accessToken)
        return owner.accessToken to mate.accessToken
    }

    @Test
    fun `a live share is listed for the team and disappears when the host disconnects`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()

        val listed: SharesResponse = client.shares(mateToken).body()
        assertEquals(1, listed.shares.size)
        assertEquals("s1", listed.shares.first().shareId)
        assertEquals("owner@x.io", listed.shares.first().hostAccountId)
        assertEquals(meta, listed.shares.first().meta)

        host.close()
        awaitUntil { services.shares.list(teamId).isEmpty() }
        val after: SharesResponse = client.shares(mateToken).body()
        assertTrue(after.shares.isEmpty(), "a share must not outlive its host's socket")
    }

    @Test
    fun `frames are relayed between the host and a viewer`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        val guest = client.webSocketSession(joinPath("s1")) { bearerAuth(mateToken) }

        host.send(Frame.Binary(true, "sealed-output".encodeToByteArray()))
        assertContentEquals("sealed-output".encodeToByteArray(), withTimeout(5_000) { guest.nextBinary() })

        guest.send(Frame.Binary(true, "sealed-input".encodeToByteArray()))
        assertContentEquals("sealed-input".encodeToByteArray(), withTimeout(5_000) { host.nextBinary() })

        guest.close()
        host.close()
    }

    @Test
    fun `a viewer that joins late is caught up with the buffered output`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        host.send(Frame.Binary(true, "earlier".encodeToByteArray()))

        // Joining now must show what is already on the host's screen, not a blank terminal.
        val guest = client.webSocketSession(joinPath("s1")) { bearerAuth(mateToken) }
        host.send(Frame.Binary(true, "later".encodeToByteArray()))

        assertContentEquals("earlier".encodeToByteArray(), withTimeout(5_000) { guest.nextBinary() })
        assertContentEquals("later".encodeToByteArray(), withTimeout(5_000) { guest.nextBinary() })

        guest.close()
        host.close()
    }

    @Test
    fun `the host is told which viewers come and go`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        val guest = client.webSocketSession(joinPath("s1")) { bearerAuth(mateToken) }
        val joined = withTimeout(5_000) { host.nextText() }
        assertEquals("viewers:1", joined.substringBeforeLast(':'))
        // The account travels base64-encoded so an id can never introduce a separator of its own.
        assertEquals(
            "mate@x.io",
            java.util.Base64.getDecoder().decode(joined.substringAfterLast(':')).decodeToString(),
        )

        guest.close()
        assertEquals("viewers:0:", withTimeout(5_000) { host.nextText() })
        host.close()
    }

    @Test
    fun `the viewers stream ends when the host stops sharing`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        val guest = client.webSocketSession(joinPath("s1")) { bearerAuth(mateToken) }
        services.awaitViewers(1)

        host.close()

        assertEquals(CloseReason.Codes.NORMAL.code, withTimeout(5_000) { guest.closeReason.await() }?.code)
    }

    @Test
    fun `a viewer removed from the team mid-session is disconnected`() = testApplication {
        // The JWT is only checked at handshake, so a member removed while watching would otherwise
        // keep receiving a live shell until they chose to disconnect.
        val services = testServices(shareAccessRecheckMillis = 50)
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        val guest = client.webSocketSession(joinPath("s1")) { bearerAuth(mateToken) }
        services.awaitViewers(1)

        services.teams.removeMember(teamId, "mate@x.io")

        val reason = withTimeout(5_000) { guest.closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        host.close()
    }

    @Test
    fun `an account outside the team can neither list nor watch`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, _) = client.teamOfTwo()
        val outsider = client.registerAccount("out@x.io", pw, deviceId = "d-out")

        assertEquals(HttpStatusCode.NotFound, client.shares(outsider.accessToken).status)

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        client.webSocket(joinPath("s1"), request = { bearerAuth(outsider.accessToken) }) {
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
        assertEquals(0, services.shares.list(teamId).single().viewers)
        host.close()
    }

    @Test
    fun `an invited member who has not accepted cannot host`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val pending = client.registerAccount("pending@x.io", pw, deviceId = "d-pending")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "pending@x.io")

        client.webSocket(hostPath("s1", meta), request = { bearerAuth(pending.accessToken) }) {
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
        assertTrue(services.shares.list(teamId).isEmpty())
    }

    @Test
    fun `a second host on the same share id is refused`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        services.awaitShare()
        client.webSocket(hostPath("s1", meta), request = { bearerAuth(mateToken) }) {
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
        }

        // The original host keeps the share.
        assertEquals("owner@x.io", services.shares.list(teamId).single().hostAccountId)
        host.close()
    }

    @Test
    fun `a malformed share id is refused before anything is registered`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)

        client.webSocket(hostPath("UPPER-case", meta), request = { bearerAuth(owner.accessToken) }) {
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
        }
        assertTrue(services.shares.list(teamId).isEmpty())
    }

    @Test
    fun `an oversized meta blob is refused`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)

        client.webSocket(hostPath("s1", "A".repeat(4096)), request = { bearerAuth(owner.accessToken) }) {
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
        }
        assertTrue(services.shares.list(teamId).isEmpty())
    }

    @Test
    fun `members are told over the sync socket that the share directory changed`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = wsClient()
        val (ownerToken, mateToken) = client.teamOfTwo()

        val sync = client.webSocketSession("/sync") { bearerAuth(mateToken) }
        withTimeout(5_000) { services.notifier.subscriptions.first { it >= WS_SUBSCRIPTIONS } }

        val host = client.webSocketSession(hostPath("s1", meta)) { bearerAuth(ownerToken) }
        assertEquals("shares:$teamId", withTimeout(5_000) { sync.nextText() })

        // Ending a share is a directory change too — a stale entry would sit on every member's list.
        host.close()
        assertEquals("shares:$teamId", withTimeout(5_000) { sync.nextText() })
        sync.close()
    }

    private suspend fun app.skerry.server.Services.awaitShare() =
        awaitUntil { shares.list(teamId).isNotEmpty() }

    private suspend fun app.skerry.server.Services.awaitViewers(count: Int) =
        awaitUntil { shares.list(teamId).firstOrNull()?.viewers == count }

    /** Next binary frame, skipping the server's own text control frames. */
    private suspend fun DefaultClientWebSocketSession.nextBinary(): ByteArray {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) return frame.readBytes()
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextText(): String {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text) return frame.readText()
        }
    }
}
