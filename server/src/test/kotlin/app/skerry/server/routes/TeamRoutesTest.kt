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
import app.skerry.sync.wire.TeamMembersResponse
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

class TeamRoutesTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"
    private val password = "auth-key-hex-abc123"
    private val teamId = "team-0001"

    private fun record(id: String, version: Long = 1, type: String = "HOST") =
        RecordDto(id, type, version, "2026-07-04T00:00:00Z", "devA", false, byteArrayOf(1, 2).b64())

    private suspend fun HttpClient.publishKey(token: String, key: ByteArray) =
        put("/account/key") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PublishKeyRequest(key.b64()))
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

    @Test
    fun `full team lifecycle create invite accept push pull`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val aliceTokens = client.registerAccount(alice, password)
        val bobTokens = client.registerAccount(bob, password, deviceId = "dev-bob")

        // ключи: до публикации 404, после — отдаётся
        assertEquals(HttpStatusCode.NotFound, client.get("/account/keys/$bob") { bearerAuth(aliceTokens.accessToken) }.status)
        assertEquals(HttpStatusCode.OK, client.publishKey(bobTokens.accessToken, ByteArray(32) { 7 }).status)
        val bobKey: AccountKeyResponse = client.get("/account/keys/$bob") { bearerAuth(aliceTokens.accessToken) }.body()
        assertEquals(ByteArray(32) { 7 }.b64(), bobKey.publicKey)

        // создание команды; дубликат id — 409
        assertEquals(HttpStatusCode.Created, client.createTeam(aliceTokens.accessToken).status)
        assertEquals(HttpStatusCode.Conflict, client.createTeam(aliceTokens.accessToken).status)

        // приглашение
        val envelope = byteArrayOf(9, 9, 9)
        assertEquals(HttpStatusCode.Created, client.invite(aliceTokens.accessToken, bob, envelope).status)
        assertEquals(HttpStatusCode.Conflict, client.invite(aliceTokens.accessToken, bob, envelope).status)

        // Боб видит приглашение с конвертом
        val bobTeams: TeamsResponse = client.get("/teams") { bearerAuth(bobTokens.accessToken) }.body()
        val invitedTeam = bobTeams.teams.single()
        assertEquals("invited", invitedTeam.status)
        assertEquals(envelope.b64(), invitedTeam.envelope)
        assertEquals(2, invitedTeam.memberCount)

        // до принятия записи команды недоступны (403)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/teams/$teamId/records?since=0") { bearerAuth(bobTokens.accessToken) }.status,
        )

        // принятие: конверт очищен, статус active
        assertEquals(HttpStatusCode.OK, client.post("/teams/$teamId/accept") { bearerAuth(bobTokens.accessToken) }.status)
        val accepted: TeamsResponse = client.get("/teams") { bearerAuth(bobTokens.accessToken) }.body()
        assertEquals("active", accepted.teams.single().status)
        assertNull(accepted.teams.single().envelope)

        // Алиса пушит запись — Боб её видит в дельте
        val push: PushResponse = client.put("/teams/$teamId/records") {
            bearerAuth(aliceTokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(record("r1"))))
        }.body()
        assertEquals(1L, push.cursor)

        val delta: RecordsResponse = client.get("/teams/$teamId/records?since=0") { bearerAuth(bobTokens.accessToken) }.body()
        assertEquals("r1", delta.records.single().id)

        // участники видны обоим
        val members: TeamMembersResponse = client.get("/teams/$teamId/members") { bearerAuth(bobTokens.accessToken) }.body()
        assertEquals(setOf(alice, bob), members.members.map { it.accountId }.toSet())
        assertNotNull(members.members.single { it.role == "owner" && it.accountId == alice })
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

        // посторонний не видит команду (404, не 403 — не раскрываем существование)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/$teamId/members") { bearerAuth(eveTokens.accessToken) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/teams/$teamId/records?since=0") { bearerAuth(eveTokens.accessToken) }.status,
        )

        // рядовой участник не приглашает и не удаляет команду
        assertEquals(HttpStatusCode.Forbidden, client.invite(bobTokens.accessToken, "eve@example.com", byteArrayOf(2)).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/teams/$teamId") { bearerAuth(bobTokens.accessToken) }.status)

        // владельца нельзя удалить даже владельцу
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/teams/$teamId/members/$alice") { bearerAuth(aliceTokens.accessToken) }.status,
        )

        // участник может выйти сам; после выхода — команду не видит
        assertEquals(HttpStatusCode.OK, client.delete("/teams/$teamId/members/$bob") { bearerAuth(bobTokens.accessToken) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/teams/$teamId/records?since=0") { bearerAuth(bobTokens.accessToken) }.status,
        )

        // после удаления команды владельцем — 404 всем
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

        // SETTINGS — per-account тип, в team-scope запрещён
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
