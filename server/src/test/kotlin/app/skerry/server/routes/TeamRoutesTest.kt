package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.AccountKeyResponse
import app.skerry.sync.wire.PublishKeyRequest
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.PushResponse
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamInviteRequest
import app.skerry.sync.wire.RekeyEnvelopeDto
import app.skerry.sync.wire.TeamMembersResponse
import app.skerry.sync.wire.TeamRekeyRequest
import app.skerry.sync.wire.TeamsResponse
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamRoutesTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"
    private val password = "auth-key-hex-abc123"
    private val teamId = "team-0001"

    private fun record(id: String, version: Long = 1, type: String = "HOST") =
        RecordDto(id, type, version, "2026-07-04T00:00:00Z", "devA", false, byteArrayOf(1, 2).b64())

    private suspend fun HttpClient.publishKey(token: String, key: ByteArray, signKey: ByteArray = ByteArray(32) { 5 }) =
        put("/account/key") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PublishKeyRequest(key.b64(), signKey.b64()))
        }

    private suspend fun HttpClient.createTeam(token: String, id: String = teamId) =
        post("/teams") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest(id))
        }

    private suspend fun HttpClient.invite(token: String, target: String, envelope: ByteArray) =
        post("/teams/$teamId/members") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamInviteRequest(target, envelope.b64()))
        }

    private suspend fun HttpClient.rekey(token: String, newEpoch: Long, envelopes: Map<String, ByteArray>) =
        post("/teams/$teamId/rekey") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamRekeyRequest(newEpoch, envelopes.map { (id, env) -> RekeyEnvelopeDto(id, env.b64()) }))
        }

    /**
     * A team id is client-chosen and ends up as a vault file name on every member's device, which
     * is why the client refuses anything outside `[a-z0-9-]{1,64}` (`TeamScopeRef.isSafeId`). The
     * server accepted 128 characters of anything, so an id could be stored that no member can
     * adopt — and one longer than the column takes fails the insert, answering 500 instead of 400.
     * The scope route already validates exactly this; the team route did not.
     */
    @Test
    fun `a team id the clients could never use is refused`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(alice, password)

        listOf(
            "../../escape", // a path traversal in a name that becomes a file
            "Team-One", // uppercase: the client's own check refuses it
            "team one", // whitespace
            "a".repeat(65), // longer than Teams.id (varchar(64))
        ).forEach { id ->
            assertEquals(
                HttpStatusCode.BadRequest,
                client.createTeam(tokens.accessToken, id).status,
                "team id \"$id\" was accepted",
            )
        }

        // The shape every client can actually use is still accepted.
        assertEquals(HttpStatusCode.Created, client.createTeam(tokens.accessToken, "team-42").status)
    }

    @Test
    fun `rekey bumps the epoch, distributes key envelopes, and enforces monotonicity and role`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        val bobTokens = client.registerAccount(bob, password, deviceId = "dev-bob")
        client.createTeam(aliceTokens.accessToken)
        client.invite(aliceTokens.accessToken, bob, byteArrayOf(1))
        client.post("/teams/$teamId/accept") { bearerAuth(bobTokens.accessToken) }

        val bobEnv = byteArrayOf(2, 2, 2)
        val aliceEnv = byteArrayOf(3, 3, 3)

        // Non-monotonic epoch (current is 0, next must be 1) → 409.
        assertEquals(HttpStatusCode.Conflict, client.rekey(aliceTokens.accessToken, 2, mapOf(bob to bobEnv)).status)
        // Envelope for a non-member → 400.
        assertEquals(HttpStatusCode.BadRequest, client.rekey(aliceTokens.accessToken, 1, mapOf("ghost@x" to bobEnv)).status)
        // A viewer (bob) can't rotate → 403.
        assertEquals(HttpStatusCode.Forbidden, client.rekey(bobTokens.accessToken, 1, mapOf(bob to bobEnv)).status)

        // Owner rotates to epoch 1.
        assertEquals(HttpStatusCode.OK, client.rekey(aliceTokens.accessToken, 1, mapOf(bob to bobEnv, alice to aliceEnv)).status)

        // Bob sees the new epoch and his re-sealed key envelope.
        val bobTeams: TeamsResponse = client.get("/teams") { bearerAuth(bobTokens.accessToken) }.body()
        val team = bobTeams.teams.single()
        assertEquals(1L, team.keyEpoch)
        assertEquals(bobEnv.b64(), team.keyEnvelope)

        // Replaying epoch 1 is rejected (monotonic).
        assertEquals(HttpStatusCode.Conflict, client.rekey(aliceTokens.accessToken, 1, mapOf(bob to bobEnv)).status)
    }

    @Test
    fun `full team lifecycle create invite accept push pull`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        val bobTokens = client.registerAccount(bob, password, deviceId = "dev-bob")

        // keys: 404 before publishing, served after
        assertEquals(HttpStatusCode.NotFound, client.get("/account/keys/$bob") { bearerAuth(aliceTokens.accessToken) }.status)
        assertEquals(HttpStatusCode.OK, client.publishKey(bobTokens.accessToken, ByteArray(32) { 7 }).status)
        val bobKey: AccountKeyResponse = client.get("/account/keys/$bob") { bearerAuth(aliceTokens.accessToken) }.body()
        assertEquals(ByteArray(32) { 7 }.b64(), bobKey.publicKey)

        // create a team; duplicate id -> 409
        assertEquals(HttpStatusCode.Created, client.createTeam(aliceTokens.accessToken).status)
        assertEquals(HttpStatusCode.Conflict, client.createTeam(aliceTokens.accessToken).status)

        // invite
        val envelope = byteArrayOf(9, 9, 9)
        assertEquals(HttpStatusCode.Created, client.invite(aliceTokens.accessToken, bob, envelope).status)
        assertEquals(HttpStatusCode.Conflict, client.invite(aliceTokens.accessToken, bob, envelope).status)

        // Bob sees the invite with the envelope
        val bobTeams: TeamsResponse = client.get("/teams") { bearerAuth(bobTokens.accessToken) }.body()
        val invitedTeam = bobTeams.teams.single()
        assertEquals("invited", invitedTeam.status)
        assertEquals(envelope.b64(), invitedTeam.envelope)
        assertEquals(2, invitedTeam.memberCount)

        // team records are inaccessible before accepting (403)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/teams/$teamId/records?since=0") { bearerAuth(bobTokens.accessToken) }.status,
        )

        // accept: envelope cleared, status active
        assertEquals(HttpStatusCode.OK, client.post("/teams/$teamId/accept") { bearerAuth(bobTokens.accessToken) }.status)
        val accepted: TeamsResponse = client.get("/teams") { bearerAuth(bobTokens.accessToken) }.body()
        assertEquals("active", accepted.teams.single().status)
        assertNull(accepted.teams.single().envelope)

        // Alice pushes a record; Bob sees it in the delta
        val push: PushResponse = client.put("/teams/$teamId/records") {
            bearerAuth(aliceTokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(record("r1"))))
        }.body()
        assertEquals(1L, push.cursor)

        val delta: RecordsResponse = client.get("/teams/$teamId/records?since=0") { bearerAuth(bobTokens.accessToken) }.body()
        assertEquals("r1", delta.records.single().id)

        // members are visible to both
        val members: TeamMembersResponse = client.get("/teams/$teamId/members") { bearerAuth(bobTokens.accessToken) }.body()
        assertEquals(setOf(alice, bob), members.members.map { it.accountId }.toSet())
        assertNotNull(members.members.single { it.role == "owner" && it.accountId == alice })
    }

    @Test
    fun `a runbook is a shareable team record, an unknown type still is not`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        client.createTeam(aliceTokens.accessToken)

        suspend fun push(type: String) = client.put("/teams/$teamId/records") {
            bearerAuth(aliceTokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(record("r-$type", type = type))))
        }

        // Runbooks are shared with a team like hosts and snippets; the client offers it, so the
        // server has to accept it — otherwise the share writes a local copy nobody else ever sees.
        assertEquals(HttpStatusCode.OK, push("RUNBOOK").status)
        val delta: RecordsResponse = client.get("/teams/$teamId/records?since=0") { bearerAuth(aliceTokens.accessToken) }.body()
        assertEquals("r-RUNBOOK", delta.records.single().id)
        // The allowlist is still an allowlist: a type the team zone knows nothing about is refused.
        assertEquals(HttpStatusCode.BadRequest, push("TERMINAL_HISTORY").status)
    }

    @Test
    fun `members report when each account was last seen, across all of its devices`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val before = System.currentTimeMillis()
        val aliceTokens = client.registerAccount(alice, password)
        val bobTokens = client.registerAccount(bob, password, deviceId = "dev-bob")
        client.createTeam(aliceTokens.accessToken)
        client.invite(aliceTokens.accessToken, bob, byteArrayOf(1))
        client.post("/teams/$teamId/accept") { bearerAuth(bobTokens.accessToken) }

        // Bob signs in on a second device; the freshest of an account's devices is what the team sees.
        val secondLogin = System.currentTimeMillis()
        client.srpLogin(bob, password, deviceId = "dev-bob-2", deviceName = "Phone")

        val members: TeamMembersResponse = client.get("/teams/$teamId/members") { bearerAuth(aliceTokens.accessToken) }.body()
        val aliceSeen = members.members.single { it.accountId == alice }.lastSeenAt
        val bobSeen = members.members.single { it.accountId == bob }.lastSeenAt

        assertNotNull(aliceSeen)
        assertTrue(aliceSeen >= before)
        assertNotNull(bobSeen)
        assertTrue(bobSeen >= secondLogin, "the newer device's activity must win: $bobSeen < $secondLogin")
    }

    @Test
    fun `members report how many devices each account has paired, revoked ones excluded`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        val bobTokens = client.registerAccount(bob, password, deviceId = "dev-bob")
        client.createTeam(aliceTokens.accessToken)
        client.invite(aliceTokens.accessToken, bob, byteArrayOf(1))
        client.post("/teams/$teamId/accept") { bearerAuth(bobTokens.accessToken) }

        client.srpLogin(bob, password, deviceId = "dev-bob-2", deviceName = "Phone")
        val thirdLogin = System.currentTimeMillis()
        client.srpLogin(bob, password, deviceId = "dev-bob-3", deviceName = "Tablet")
        // A revoked device holds no team key any more, so it is not one of the team's devices.
        client.delete("/devices/dev-bob-3") { bearerAuth(bobTokens.accessToken) }

        val members: TeamMembersResponse = client.get("/teams/$teamId/members") { bearerAuth(aliceTokens.accessToken) }.body()
        val bobRow = members.members.single { it.accountId == bob }
        assertEquals(1, members.members.single { it.accountId == alice }.devices)
        assertEquals(2, bobRow.devices)
        // The revoked device was bob's most recent activity, and it still counts for freshness:
        // revoking says the key is gone, not that the sign-in never happened.
        val bobSeen = bobRow.lastSeenAt
        assertNotNull(bobSeen)
        assertTrue(bobSeen >= thirdLogin, "the revoked device's activity must still count: $bobSeen < $thirdLogin")
    }

    @Test
    fun `an account that has not accepted the invite has no device count in the members list`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        client.registerAccount(bob, password, deviceId = "dev-bob")
        client.createTeam(aliceTokens.accessToken)
        client.invite(aliceTokens.accessToken, bob, byteArrayOf(1))

        val members: TeamMembersResponse = client.get("/teams/$teamId/members") { bearerAuth(aliceTokens.accessToken) }.body()
        assertNull(members.members.single { it.accountId == bob }.devices)
        assertEquals(1, members.members.single { it.accountId == alice }.devices)
    }

    @Test
    fun `ACL non-members get 404 members cannot invite owner cannot be removed`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        val bobTokens = client.registerAccount(bob, password, deviceId = "dev-bob")
        val eveTokens = client.registerAccount("eve@example.com", password, deviceId = "dev-eve")

        client.createTeam(aliceTokens.accessToken)
        client.invite(aliceTokens.accessToken, bob, byteArrayOf(1))
        client.post("/teams/$teamId/accept") { bearerAuth(bobTokens.accessToken) }

        // a non-member does not see the team (404, not 403, to avoid revealing existence)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/$teamId/members") { bearerAuth(eveTokens.accessToken) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/teams/$teamId/records?since=0") { bearerAuth(eveTokens.accessToken) }.status,
        )

        // a regular member cannot invite or delete the team
        assertEquals(HttpStatusCode.Forbidden, client.invite(bobTokens.accessToken, "eve@example.com", byteArrayOf(2)).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/teams/$teamId") { bearerAuth(bobTokens.accessToken) }.status)

        // the owner cannot be removed, even by themselves
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/teams/$teamId/members/$alice") { bearerAuth(aliceTokens.accessToken) }.status,
        )

        // a member can leave on their own; after leaving they no longer see the team
        assertEquals(HttpStatusCode.OK, client.delete("/teams/$teamId/members/$bob") { bearerAuth(bobTokens.accessToken) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/teams/$teamId/records?since=0") { bearerAuth(bobTokens.accessToken) }.status,
        )

        // after the owner deletes the team, everyone gets 404
        assertEquals(HttpStatusCode.OK, client.delete("/teams/$teamId") { bearerAuth(aliceTokens.accessToken) }.status)
        val gone: TeamsResponse = client.get("/teams") { bearerAuth(aliceTokens.accessToken) }.body()
        assertEquals(0, gone.teams.size)
    }

    @Test
    fun `team records reject types outside the team scope`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tokens = client.registerAccount(alice, password)
        client.createTeam(tokens.accessToken)

        // SETTINGS is a per-account type, forbidden in team scope
        val resp = client.put("/teams/$teamId/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(record("r1", type = "SETTINGS"))))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `publish key validates size and invite requires an existing account`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tokens = client.registerAccount(alice, password)
        client.createTeam(tokens.accessToken)

        assertEquals(HttpStatusCode.BadRequest, client.publishKey(tokens.accessToken, ByteArray(16)).status)
        assertEquals(HttpStatusCode.NotFound, client.invite(tokens.accessToken, "ghost@example.com", byteArrayOf(1)).status)
    }
}
