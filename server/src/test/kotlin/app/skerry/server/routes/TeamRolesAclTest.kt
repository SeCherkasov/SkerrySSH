package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.TeamActivityResponse
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamInviteRequest
import app.skerry.sync.wire.TeamRoleChangeRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ACL for granular roles (owner/admin/editor/viewer), role changes, and the team-scoped audit log. */
class TeamRolesAclTest {

    private val teamId = "team-acl-1"
    private val pw = "auth-key-hex"

    private suspend fun HttpClient.createTeam(token: String) = post("/teams") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(TeamCreateRequest(teamId))
    }

    private suspend fun HttpClient.invite(token: String, target: String, role: String) =
        post("/teams/$teamId/members") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamInviteRequest(target, byteArrayOf(1, 2, 3).b64(), role))
        }

    private suspend fun HttpClient.accept(token: String) =
        post("/teams/$teamId/accept") { bearerAuth(token) }

    private suspend fun HttpClient.changeRole(token: String, target: String, role: String) =
        put("/teams/$teamId/members/$target/role") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamRoleChangeRequest(role))
        }

    private suspend fun HttpClient.pushRecord(token: String) = put("/teams/$teamId/records") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-07-05T00:00:00Z", "devA", false, byteArrayOf(9).b64()))))
    }

    @Test
    fun `write is gated by role - editor writes, viewer is forbidden`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        val viewer = client.registerAccount("viewer@x.io", pw, deviceId = "d-viewer")

        client.createTeam(owner.accessToken)
        assertEquals(HttpStatusCode.Created, client.invite(owner.accessToken, "editor@x.io", "editor").status)
        assertEquals(HttpStatusCode.Created, client.invite(owner.accessToken, "viewer@x.io", "viewer").status)
        client.accept(editor.accessToken)
        client.accept(viewer.accessToken)

        assertEquals(HttpStatusCode.OK, client.pushRecord(editor.accessToken).status)
        assertEquals(HttpStatusCode.Forbidden, client.pushRecord(viewer.accessToken).status)
    }

    @Test
    fun `member management is gated - editor cannot invite`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.registerAccount("ghost@x.io", pw, deviceId = "d-ghost")

        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor")
        client.accept(editor.accessToken)

        assertEquals(HttpStatusCode.Forbidden, client.invite(editor.accessToken, "ghost@x.io", "viewer").status)
    }

    @Test
    fun `anti-escalation - admin cannot grant admin or owner`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val admin = client.registerAccount("admin@x.io", pw, deviceId = "d-admin")
        client.registerAccount("nn@x.io", pw, deviceId = "d-nn")

        client.createTeam(owner.accessToken)
        assertEquals(HttpStatusCode.Created, client.invite(owner.accessToken, "admin@x.io", "admin").status)
        client.accept(admin.accessToken)

        // admin can invite editor/viewer but not admin/owner (privilege escalation)
        assertEquals(HttpStatusCode.Created, client.invite(admin.accessToken, "nn@x.io", "editor").status)
        assertEquals(HttpStatusCode.Forbidden, client.invite(admin.accessToken, "x2@x.io", "admin").status)
        assertEquals(HttpStatusCode.Forbidden, client.invite(admin.accessToken, "x3@x.io", "owner").status)
    }

    @Test
    fun `role change - owner promotes, admin cannot escalate`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val admin = client.registerAccount("admin@x.io", pw, deviceId = "d-admin")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")

        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "admin@x.io", "admin"); client.accept(admin.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor"); client.accept(editor.accessToken)

        // owner can change editor->admin
        assertEquals(HttpStatusCode.OK, client.changeRole(owner.accessToken, "editor@x.io", "admin").status)
        // the owner role is fixed; even the owner cannot change it (anti-escalation, 403)
        assertEquals(HttpStatusCode.Forbidden, client.changeRole(owner.accessToken, "owner@x.io", "viewer").status)
        // admin cannot promote anyone to admin (escalation)
        assertEquals(HttpStatusCode.Forbidden, client.changeRole(admin.accessToken, "editor@x.io", "admin").status)
        // admin cannot demote editor->viewer either, since editor is now admin and admin can't touch admin
        assertEquals(HttpStatusCode.Forbidden, client.changeRole(admin.accessToken, "editor@x.io", "viewer").status)
    }

    @Test
    fun `audit log visible to owner and admin, not to editor or viewer`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val admin = client.registerAccount("admin@x.io", pw, deviceId = "d-admin")
        val viewer = client.registerAccount("viewer@x.io", pw, deviceId = "d-viewer")

        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "admin@x.io", "admin"); client.accept(admin.accessToken)
        client.invite(owner.accessToken, "viewer@x.io", "viewer"); client.accept(viewer.accessToken)

        val log: TeamActivityResponse = client.get("/teams/$teamId/activity") {
            bearerAuth(owner.accessToken)
        }.body()
        assertTrue(log.entries.any { it.event == "team.create" })
        assertTrue(log.entries.any { it.event == "team.invite" })

        assertEquals(HttpStatusCode.OK, client.get("/teams/$teamId/activity") { bearerAuth(admin.accessToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/teams/$teamId/activity") { bearerAuth(viewer.accessToken) }.status)
    }
}
