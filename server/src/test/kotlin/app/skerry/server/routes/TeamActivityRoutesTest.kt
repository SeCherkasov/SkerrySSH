package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.TeamActivityResponse
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamInviteRequest
import app.skerry.sync.wire.TeamScopeCreateRequest
import app.skerry.sync.wire.TeamScopeGrantRequest
import app.skerry.sync.wire.TeamSessionEventRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The team activity feed: what a push writes into the audit log (one event per record that actually
 * changed) and the client-reported session events.
 */
class TeamActivityRoutesTest {

    private val teamId = "team-feed-1"
    private val pw = "auth-key-hex"

    private suspend fun HttpClient.createTeam(token: String) = post("/teams") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(TeamCreateRequest(teamId))
    }

    private suspend fun HttpClient.push(
        token: String,
        records: List<RecordDto>,
        scope: String = "",
    ): HttpResponse {
        val query = if (scope.isEmpty()) "" else "?scope=$scope"
        return put("/teams/$teamId/records$query") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(records))
        }
    }

    private fun host(id: String, version: Long, deleted: Boolean = false, type: String = "HOST") =
        RecordDto(id, type, version, "2026-07-26T00:00:00Z", "devA", deleted, byteArrayOf(version.toByte()).b64())

    private suspend fun HttpClient.activity(token: String): TeamActivityResponse =
        get("/teams/$teamId/activity") { bearerAuth(token) }.body()

    private suspend fun HttpClient.reportSession(
        token: String,
        recordId: String,
        kind: String,
        durationSec: Long? = null,
    ) = post("/teams/$teamId/session-events") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(TeamSessionEventRequest(recordId, kind, durationSec))
    }

    @Test
    fun `each changed record becomes its own event, unchanged ones none`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)

        client.push(owner.accessToken, listOf(host("h-1", 1), host("h-2", 1)))
        client.push(owner.accessToken, listOf(host("h-1", 2), host("h-2", 1))) // h-2 unchanged
        client.push(owner.accessToken, listOf(host("h-1", 3, deleted = true)))

        val events = client.activity(owner.accessToken).entries
        // Newest first. The second push must not report h-2 again: a client pushes all of its
        // records on every sync cycle, and an unchanged one is not somebody's edit.
        assertEquals(
            listOf(
                "team.record_remove" to "h-1",
                "team.record_change" to "h-1",
                "team.record_share" to "h-2",
                "team.record_share" to "h-1",
            ),
            events.filter { it.event.startsWith("team.record") }.map { it.event to it.recordId },
        )
        val newest = events.first()
        assertEquals("HOST", newest.recordType)
        assertEquals("", newest.scopeId)
        assertEquals("owner@x.io", newest.actorAccountId)
    }

    @Test
    fun `a scoped push carries its scope, and a bulk push collapses into one event`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)
        assertEquals(
            HttpStatusCode.Created,
            client.post("/teams/$teamId/scopes") {
                bearerAuth(owner.accessToken)
                contentType(ContentType.Application.Json)
                setBody(TeamScopeCreateRequest("prod", byteArrayOf(1, 2, 3).b64()))
            }.status,
        )

        client.push(owner.accessToken, listOf(host("s-1", 1)), scope = "prod")
        assertEquals("prod", client.activity(owner.accessToken).entries.first().scopeId)

        // Batch sizes are literal on purpose: derived from TEAM_RECORD_EVENT_LIMIT they would follow
        // the constant around and stop testing the threshold at all. A limit change should land here.
        assertEquals(10, TEAM_RECORD_EVENT_LIMIT)

        // A batch at the limit still names every record.
        client.push(owner.accessToken, (1..10).map { host("a-$it", 1) })
        val listed = client.activity(owner.accessToken).entries
        assertEquals(10, listed.count { it.recordId?.startsWith("a-") == true })

        // A key rotation re-encrypts every record in the space and pushes them all at once. Listing
        // those would bury the feed in "changed" rows that say nothing about anyone's intent, so a
        // batch past the limit is summarised instead.
        client.push(owner.accessToken, (1..11).map { host("b-$it", 1) })
        val events = client.activity(owner.accessToken).entries
        assertEquals("team.push", events.first().event)
        assertTrue(events.first().detail.contains("11"))
        assertNull(events.first().recordId)
        assertTrue(events.none { it.recordId?.startsWith("b-") == true })
    }

    @Test
    fun `session events are reported by members and shown with the record subject`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.createTeam(owner.accessToken)
        client.post("/teams/$teamId/members") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamInviteRequest("editor@x.io", byteArrayOf(1, 2, 3).b64(), "editor"))
        }
        client.post("/teams/$teamId/accept") { bearerAuth(editor.accessToken) }
        client.push(owner.accessToken, listOf(host("h-1", 1)))

        assertEquals(HttpStatusCode.Created, client.reportSession(editor.accessToken, "h-1", "open").status)
        assertEquals(
            HttpStatusCode.Created,
            client.reportSession(editor.accessToken, "h-1", "record", durationSec = 90).status,
        )

        val events = client.activity(owner.accessToken).entries
        val recorded = events.first()
        assertEquals("team.session_record", recorded.event)
        assertEquals("h-1", recorded.recordId)
        assertEquals("HOST", recorded.recordType)
        assertEquals(90, recorded.durationSec)
        assertEquals("editor@x.io", recorded.actorAccountId)
        assertEquals("team.session_open", events[1].event)
    }

    @Test
    fun `a session event needs a real record of the team and an unknown kind is refused`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val stranger = client.registerAccount("stranger@x.io", pw, deviceId = "d-stranger")
        client.createTeam(owner.accessToken)
        client.push(owner.accessToken, listOf(host("h-1", 1)))

        // A record nobody shared into this team: nothing to report about.
        assertEquals(HttpStatusCode.NotFound, client.reportSession(owner.accessToken, "nope", "open").status)
        assertEquals(HttpStatusCode.BadRequest, client.reportSession(owner.accessToken, "h-1", "sudo").status)
        // Not a member — the team must not even be confirmed to exist.
        assertEquals(HttpStatusCode.NotFound, client.reportSession(stranger.accessToken, "h-1", "open").status)
        assertTrue(client.activity(owner.accessToken).entries.none { it.event.startsWith("team.session") })
    }

    @Test
    fun `a reported duration is clamped, and a malformed report is refused`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)
        client.push(owner.accessToken, listOf(host("h-1", 1)))

        // A client's number is not to be trusted: a negative one is not a duration, and a month-long
        // "session" must not be presented to a reader as fact.
        client.reportSession(owner.accessToken, "h-1", "record", durationSec = -30)
        assertEquals(0, client.activity(owner.accessToken).entries.first().durationSec)
        client.reportSession(owner.accessToken, "h-1", "open", durationSec = Long.MAX_VALUE)
        assertEquals(30L * 24 * 3600, client.activity(owner.accessToken).entries.first().durationSec)

        assertEquals(HttpStatusCode.BadRequest, client.reportSession(owner.accessToken, "", "open").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.reportSession(owner.accessToken, "x".repeat(500), "open").status,
        )
    }

    @Test
    fun `a non-member learns nothing from a malformed report either`() = testApplication {
        // Membership is checked before the body: otherwise a stranger sending a bad kind would get a
        // 400 confirming the route (and the team) exists, where a well-formed one gets 404.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val stranger = client.registerAccount("stranger@x.io", pw, deviceId = "d-stranger")
        client.createTeam(owner.accessToken)
        client.push(owner.accessToken, listOf(host("h-1", 1)))

        assertEquals(HttpStatusCode.NotFound, client.reportSession(stranger.accessToken, "h-1", "sudo").status)
        assertEquals(HttpStatusCode.NotFound, client.reportSession(stranger.accessToken, "", "open").status)
    }

    @Test
    fun `a scoped record can only be reported by someone holding the scope`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.createTeam(owner.accessToken)
        client.post("/teams/$teamId/members") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamInviteRequest("editor@x.io", byteArrayOf(1, 2, 3).b64(), "editor"))
        }
        client.post("/teams/$teamId/accept") { bearerAuth(editor.accessToken) }
        client.post("/teams/$teamId/scopes") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamScopeCreateRequest("prod", byteArrayOf(1, 2, 3).b64()))
        }
        client.push(owner.accessToken, listOf(host("p-1", 1)), scope = "prod")

        // Without a grant the record is invisible: reporting a session on it would confirm it exists.
        assertEquals(HttpStatusCode.NotFound, client.reportSession(editor.accessToken, "p-1", "open").status)

        client.post("/teams/$teamId/scopes/prod/grants") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamScopeGrantRequest("editor@x.io", byteArrayOf(4, 5, 6).b64()))
        }
        assertEquals(HttpStatusCode.Created, client.reportSession(editor.accessToken, "p-1", "open").status)
        assertEquals("prod", client.activity(owner.accessToken).entries.first().scopeId)
    }
}
