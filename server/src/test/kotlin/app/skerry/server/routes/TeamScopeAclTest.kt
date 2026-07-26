package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import app.skerry.sync.wire.RekeyEnvelopeDto
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamInviteRequest
import app.skerry.sync.wire.TeamRekeyRequest
import app.skerry.sync.wire.TeamScopeCreateRequest
import app.skerry.sync.wire.TeamScopeGrantRequest
import app.skerry.sync.wire.TeamScopeGrantsResponse
import app.skerry.sync.wire.TeamScopesResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ACL of granular sharing. The server enforcement checked here is the first of two independent
 * layers: a member without a grant can't fetch a scope's records. The second — that they couldn't
 * decrypt them anyway — lives in the client and is what survives a compromised server.
 */
class TeamScopeAclTest {

    private val teamId = "team-scope-1"
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

    private suspend fun HttpClient.accept(token: String) = post("/teams/$teamId/accept") { bearerAuth(token) }

    private suspend fun HttpClient.createScope(token: String, scopeId: String, envelope: Byte = 1) =
        post("/teams/$teamId/scopes") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamScopeCreateRequest(scopeId, byteArrayOf(envelope).b64()))
        }

    private suspend fun HttpClient.grantScope(token: String, scopeId: String, target: String, envelope: Byte = 2) =
        post("/teams/$teamId/scopes/$scopeId/grants") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamScopeGrantRequest(target, byteArrayOf(envelope).b64()))
        }

    private suspend fun HttpClient.revokeScope(token: String, scopeId: String, target: String) =
        delete("/teams/$teamId/scopes/$scopeId/grants/$target") { bearerAuth(token) }

    private suspend fun HttpClient.listScopes(token: String) =
        get("/teams/$teamId/scopes") { bearerAuth(token) }

    private suspend fun HttpClient.rekey(token: String, epoch: Long) = post("/teams/$teamId/scopes/prod/rekey") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(TeamRekeyRequest(epoch, listOf(RekeyEnvelopeDto("owner@x.io", byteArrayOf(3).b64()))))
    }

    private suspend fun HttpClient.pull(token: String, scope: String?) =
        get("/teams/$teamId/records${if (scope == null) "" else "?scope=$scope"}") { bearerAuth(token) }

    private suspend fun HttpClient.push(token: String, scope: String?, id: String = "r1") =
        put("/teams/$teamId/records${if (scope == null) "" else "?scope=$scope"}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto(id, "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(9).b64()))))
        }

    @Test
    fun `a member without a grant can neither read nor write the scope`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val junior = client.registerAccount("junior@x.io", pw, deviceId = "d-junior")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "junior@x.io", "editor")
        client.accept(junior.accessToken)
        assertEquals(HttpStatusCode.Created, client.createScope(owner.accessToken, "prod").status)
        assertEquals(HttpStatusCode.OK, client.push(owner.accessToken, "prod").status)

        // 404 rather than 403: the existence of a scope is itself information about the team.
        assertEquals(HttpStatusCode.NotFound, client.pull(junior.accessToken, "prod").status)
        assertEquals(HttpStatusCode.NotFound, client.push(junior.accessToken, "prod", id = "r2").status)
        // Team-wide sharing is unaffected — that is what an unscoped record still means.
        assertEquals(HttpStatusCode.OK, client.pull(junior.accessToken, null).status)
        assertTrue(client.pull(junior.accessToken, null).body<RecordsResponse>().records.isEmpty())
    }

    @Test
    fun `a granted member reads the scope and stops after a revoke`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val senior = client.registerAccount("senior@x.io", pw, deviceId = "d-senior")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "senior@x.io", "editor")
        client.accept(senior.accessToken)
        client.createScope(owner.accessToken, "prod")
        client.push(owner.accessToken, "prod")

        assertEquals(HttpStatusCode.Created, client.grantScope(owner.accessToken, "prod", "senior@x.io").status)
        val page = client.pull(senior.accessToken, "prod")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals(listOf("r1"), page.body<RecordsResponse>().records.map { it.id })

        assertEquals(HttpStatusCode.OK, client.revokeScope(owner.accessToken, "prod", "senior@x.io").status)
        assertEquals(HttpStatusCode.NotFound, client.pull(senior.accessToken, "prod").status)
    }

    @Test
    fun `scopes are listed with keys only for grantees and in full only for managers`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor")
        client.accept(editor.accessToken)
        client.createScope(owner.accessToken, "prod", envelope = 5)
        client.createScope(owner.accessToken, "staging", envelope = 6)
        client.grantScope(owner.accessToken, "staging", "editor@x.io", envelope = 7)

        // An editor sees only what they were granted, with their own sealed key.
        val mine = client.listScopes(editor.accessToken).body<TeamScopesResponse>().scopes
        assertEquals(listOf("staging"), mine.map { it.scopeId })
        assertEquals(byteArrayOf(7).b64(), mine.single().envelope)

        // The owner (a manager) sees every scope; keys of scopes they hold, none of the others.
        val all = client.listScopes(owner.accessToken).body<TeamScopesResponse>().scopes.sortedBy { it.scopeId }
        assertEquals(listOf("prod", "staging"), all.map { it.scopeId })
        assertEquals(listOf(1, 2), all.map { it.memberCount })
    }

    @Test
    fun `managing scopes requires the manage-members role`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        val outsider = client.registerAccount("outsider@x.io", pw, deviceId = "d-out")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor")
        client.accept(editor.accessToken)
        client.createScope(owner.accessToken, "prod")

        assertEquals(HttpStatusCode.Forbidden, client.createScope(editor.accessToken, "rogue").status)
        assertEquals(HttpStatusCode.Forbidden, client.grantScope(editor.accessToken, "prod", "editor@x.io").status)
        assertEquals(HttpStatusCode.Forbidden, client.revokeScope(editor.accessToken, "prod", "owner@x.io").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/teams/$teamId/scopes/prod") { bearerAuth(editor.accessToken) }.status,
        )
        // A non-member gets the team's own 404, not a hint that the scope exists.
        assertEquals(HttpStatusCode.NotFound, client.listScopes(outsider.accessToken).status)
    }

    @Test
    fun `a member may give up their own scope access without a management role`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor")
        client.accept(editor.accessToken)
        client.createScope(owner.accessToken, "prod")
        client.grantScope(owner.accessToken, "prod", "editor@x.io")

        assertEquals(HttpStatusCode.OK, client.revokeScope(editor.accessToken, "prod", "editor@x.io").status)
        assertEquals(HttpStatusCode.NotFound, client.pull(editor.accessToken, "prod").status)
    }

    @Test
    fun `a scope cannot be granted to someone who is not an accepted member`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.registerAccount("pending@x.io", pw, deviceId = "d-pending")
        client.registerAccount("stranger@x.io", pw, deviceId = "d-stranger")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "pending@x.io", "editor") // invited, not accepted
        client.createScope(owner.accessToken, "prod")

        assertEquals(HttpStatusCode.NotFound, client.grantScope(owner.accessToken, "prod", "pending@x.io").status)
        assertEquals(HttpStatusCode.NotFound, client.grantScope(owner.accessToken, "prod", "stranger@x.io").status)
    }

    @Test
    fun `removing a member from the team drops their scope grants`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor")
        client.accept(editor.accessToken)
        client.createScope(owner.accessToken, "prod")
        client.grantScope(owner.accessToken, "prod", "editor@x.io")

        assertEquals(
            HttpStatusCode.OK,
            client.delete("/teams/$teamId/members/editor@x.io") { bearerAuth(owner.accessToken) }.status,
        )

        val grants = client.get("/teams/$teamId/scopes/prod/grants") { bearerAuth(owner.accessToken) }
            .body<TeamScopeGrantsResponse>().grants
        assertEquals(listOf("owner@x.io"), grants.map { it.accountId })
        assertEquals(HttpStatusCode.NotFound, client.pull(editor.accessToken, "prod").status)
    }

    @Test
    fun `scope rekey is monotonic and only for managers`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val editor = client.registerAccount("editor@x.io", pw, deviceId = "d-editor")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "editor@x.io", "editor")
        client.accept(editor.accessToken)
        client.createScope(owner.accessToken, "prod")

        assertEquals(HttpStatusCode.Forbidden, client.rekey(editor.accessToken, 1).status)
        assertEquals(HttpStatusCode.OK, client.rekey(owner.accessToken, 1).status)
        assertEquals(HttpStatusCode.Conflict, client.rekey(owner.accessToken, 1).status)
        assertEquals(HttpStatusCode.Conflict, client.rekey(owner.accessToken, 3).status)
        assertEquals(1L, client.listScopes(owner.accessToken).body<TeamScopesResponse>().scopes.single().keyEpoch)
    }

    @Test
    fun `a manager without the scope key can neither grant it nor rotate it`() = testApplication {
        // The escalation this guards: an admin deliberately left out of "prod" self-grants an ACL row,
        // then rekeys the scope with a key of their own making. Their signature is genuine, so every
        // real grantee adopts it and everything shared afterwards is readable by them. Managing the
        // team must not imply access to a scope its members were never given.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val admin = client.registerAccount("admin@x.io", pw, deviceId = "d-admin")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "admin@x.io", "admin")
        client.accept(admin.accessToken)
        client.createScope(owner.accessToken, "prod") // granted to the owner only
        client.push(owner.accessToken, "prod", id = "secret")

        assertEquals(HttpStatusCode.Forbidden, client.grantScope(admin.accessToken, "prod", "admin@x.io").status)
        assertEquals(HttpStatusCode.Forbidden, client.grantScope(admin.accessToken, "prod", "owner@x.io").status)
        assertEquals(HttpStatusCode.Forbidden, client.rekey(admin.accessToken, 1).status)

        // No ACL row was created and the scope key generation is untouched.
        assertEquals(HttpStatusCode.NotFound, client.pull(admin.accessToken, "prod").status)
        val scope = client.listScopes(owner.accessToken).body<TeamScopesResponse>().scopes.single()
        assertEquals(0L, scope.keyEpoch)
        assertEquals(1, scope.memberCount)
    }

    @Test
    fun `a manager without the key may still delete an orphaned scope`() = testApplication {
        // The escape hatch that must survive the check above: if everyone holding a scope key leaves,
        // nobody can rotate or re-grant it, so a manager has to be able to clear it away.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        val admin = client.registerAccount("admin@x.io", pw, deviceId = "d-admin")
        client.createTeam(owner.accessToken)
        client.invite(owner.accessToken, "admin@x.io", "admin")
        client.accept(admin.accessToken)
        client.createScope(owner.accessToken, "prod")

        assertEquals(
            HttpStatusCode.OK,
            client.delete("/teams/$teamId/scopes/prod") { bearerAuth(admin.accessToken) }.status,
        )
        assertTrue(client.listScopes(owner.accessToken).body<TeamScopesResponse>().scopes.isEmpty())
    }

    @Test
    fun `a malformed scope id is rejected before anything is stored`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)

        // The id becomes a vault file name on every member's device.
        assertEquals(HttpStatusCode.BadRequest, client.createScope(owner.accessToken, "../etc/passwd").status)
        assertEquals(HttpStatusCode.BadRequest, client.createScope(owner.accessToken, "Prod").status)
        assertEquals(HttpStatusCode.BadRequest, client.pull(owner.accessToken, "../x").status)
        assertTrue(client.listScopes(owner.accessToken).body<TeamScopesResponse>().scopes.isEmpty())
    }

    @Test
    fun `a record stays in its own scope when pushed from another one`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val owner = client.registerAccount("owner@x.io", pw, deviceId = "d-owner")
        client.createTeam(owner.accessToken)
        client.createScope(owner.accessToken, "prod")
        client.push(owner.accessToken, "prod", id = "h1")

        // A stale local copy re-pushed team-wide must not drag the record out of the scope: it would
        // land there encrypted under a key the scope's members don't hold.
        client.push(owner.accessToken, null, id = "h1")

        assertTrue(client.pull(owner.accessToken, null).body<RecordsResponse>().records.isEmpty())
        assertEquals(listOf("h1"), client.pull(owner.accessToken, "prod").body<RecordsResponse>().records.map { it.id })
        assertNull(client.pull(owner.accessToken, null).body<RecordsResponse>().records.firstOrNull())
    }
}
