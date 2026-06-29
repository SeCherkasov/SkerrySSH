package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.AdminAccountsResponse
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminPurgeResponse
import app.skerry.server.model.AdminRecordsResponse
import app.skerry.server.model.ChallengeRequest
import app.skerry.server.model.ChallengeResponse
import app.skerry.server.model.DevicesResponse
import app.skerry.server.model.KeysResponse
import app.skerry.server.model.PairingClaimRequest
import app.skerry.server.model.PairingClaimResponse
import app.skerry.server.model.PairingStartRequest
import app.skerry.server.model.PairingStartResponse
import app.skerry.server.model.PushRequest
import app.skerry.server.model.PushResponse
import app.skerry.server.model.RecordDto
import app.skerry.server.model.RecordsResponse
import app.skerry.server.model.RegisterRequest
import app.skerry.server.model.StatsResponse
import app.skerry.server.model.TokenResponse
import app.skerry.server.model.VerifyRequest
import app.skerry.server.model.VerifyResponse
import app.skerry.server.model.b64
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {

    private val accountId = "alice@example.com"
    private val password = "auth-key-hex-abc123"

    @Test
    fun `register then push and pull round-trips encrypted record`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val reg = srpRegister(accountId, password)
        val wrapped = byteArrayOf(7, 7, 7)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, wrapped.b64(), "devA", "Laptop A"))
        }.body()

        // wrappedDataKey возвращается как есть
        val keys: KeysResponse = client.get("/vault/keys") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(wrapped.b64(), keys.wrappedDataKey)

        // push шифроблоба
        val blob = byteArrayOf(1, 2, 3, 4)
        val push: PushResponse = client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, blob.b64()))))
        }.body()
        assertEquals(1L, push.cursor)

        // дельта since=0 отдаёт ту же запись
        val delta: RecordsResponse = client.get("/vault/records?since=0") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(1, delta.records.size)
        assertEquals(blob.b64(), delta.records.single().blob)
        assertEquals(1L, delta.cursor)
    }

    @Test
    fun `push accepts TUNNEL record type and rejects an unknown type`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(7).b64(), "devA", "Laptop A"))
        }.body()

        // TUNNEL — новый тип рабочего пространства (Phase A). Сервер хранит type как строку, но
        // фильтрует по белому списку — TUNNEL должен в нём быть, иначе синк туннелей невозможен.
        val ok = client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("t1", "TUNNEL", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))))
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        // Произвольный тип по-прежнему отвергается (защита от мусора/несовместимых клиентов).
        val bad = client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("x1", "BOGUS", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `vault routes reject missing token`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys").status)
    }

    @Test
    fun `SRP login succeeds with correct password and fails otherwise`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val reg = srpRegister(accountId, password)
        client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "A"))
        }

        // правильный пароль
        val sc = srpClient(accountId, password)
        val challenge: ChallengeResponse = client.post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(accountId))
        }.body()
        val creds = sc.step2(SRP_PARAMS, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))
        val verify = client.post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(challenge.challengeId, creds.A.toString(16), creds.M1.toString(16), "devB", "B"))
        }
        assertEquals(HttpStatusCode.OK, verify.status)
        val vr: VerifyResponse = verify.body()
        sc.step3(BigInteger(vr.m2, 16)) // не бросает = сервер аутентичен

        // неверный пароль
        val bad = srpClient(accountId, "nope")
        val ch2: ChallengeResponse = client.post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(accountId))
        }.body()
        val badCreds = bad.step2(SRP_PARAMS, BigInteger(ch2.salt, 16), BigInteger(ch2.b, 16))
        val badResp = client.post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(ch2.challengeId, badCreds.A.toString(16), badCreds.M1.toString(16), "devB", "B"))
        }
        assertEquals(HttpStatusCode.Unauthorized, badResp.status)
    }

    @Test
    fun `devices listing marks current and revoke works`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A"))
        }.body()

        val devices: DevicesResponse = client.get("/devices") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(1, devices.devices.size)
        assertTrue(devices.devices.single().current)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/devices/devA") { bearerAuth(tokens.accessToken) }.status,
        )
        // отозванное устройство больше не аутентифицируется
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys") { bearerAuth(tokens.accessToken) }.status)
    }

    @Test
    fun `revoked device re-logs in with master password and regains access`() = testApplication {
        // Точный сценарий пользователя: отозвал устройство → register=409, sync=401. С верным
        // мастер-паролем повторный вход (SRP) должен ПЕРЕАКТИВИРОВАТЬ устройство и вернуть доступ —
        // иначе аккаунт заперт навсегда. Revoke гасит текущие токены, но не банит устройство.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A"))
        }.body()

        // отзыв → старый токен мёртв
        client.delete("/devices/devA") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys") { bearerAuth(tokens.accessToken) }.status)
        // повторная регистрация невозможна (аккаунт уже есть) — клиент обязан войти
        val reReg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A"))
        }
        assertEquals(HttpStatusCode.Conflict, reReg.status)

        // повторный вход тем же устройством верным паролем → register снимает отзыв, выдаёт новые токены
        val sc = srpClient(accountId, password)
        val challenge: ChallengeResponse = client.post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(accountId))
        }.body()
        val creds = sc.step2(SRP_PARAMS, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))
        val fresh: VerifyResponse = client.post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(challenge.challengeId, creds.A.toString(16), creds.M1.toString(16), "devA", "Laptop A"))
        }.body()

        // доступ восстановлен: новый токен снова работает, устройство больше не отозвано
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(fresh.accessToken) }.status)
        val after: DevicesResponse = client.get("/devices") { bearerAuth(fresh.accessToken) }.body()
        assertEquals(false, after.devices.single { it.id == "devA" }.revoked)
    }

    @Test
    fun `pairing transfers encrypted data key to a new device`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "A"))
        }.body()

        val transferred = byteArrayOf(9, 9, 9) // dataKey, зашифрованный transferKey (сервер не видит ключ)
        val start: PairingStartResponse = client.post("/pairing/start") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(transferred.b64()))
        }.body()

        // новое устройство claim'ит по коду без входа
        val claim: PairingClaimResponse = client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, "devB", "Phone B"))
        }.body()
        assertEquals(accountId, claim.accountId)
        assertEquals(transferred.b64(), claim.encryptedDataKey)

        // выданным токеном устройство B уже работает
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(claim.accessToken) }.status)

        // код одноразовый
        assertEquals(
            HttpStatusCode.Gone,
            client.post("/pairing/claim") {
                contentType(ContentType.Application.Json)
                setBody(PairingClaimRequest(start.code, "devC", "C"))
            }.status,
        )
    }

    @Test
    fun `admin lists devices across accounts and revokes by id`() = testApplication {
        val services = testServices(adminToken = "s3cret")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A", platform = "Linux"))
        }.body()
        // push фиксирует курсор устройства (syncVersion) — открытые метаданные для консоли
        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))))
        }

        // список закрыт admin-токеном
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/devices").status)

        val listed: AdminDevicesResponse = client.get("/admin/devices") {
            header("X-Admin-Token", "s3cret")
        }.body()
        assertEquals(1, listed.devices.size)
        val d = listed.devices.single()
        assertEquals("devA", d.id)
        assertEquals(accountId, d.accountId)
        assertEquals("Laptop A", d.name)
        assertEquals("Linux", d.platform)
        assertEquals(1L, d.syncVersion)
        assertEquals(false, d.revoked)

        // отзыв тоже под токеном
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/admin/devices/devA?accountId=$accountId").status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/admin/devices/devA?accountId=$accountId") { header("X-Admin-Token", "s3cret") }.status,
        )

        // после отзыва устройство видно как revoked и не аутентифицируется
        val after: AdminDevicesResponse = client.get("/admin/devices") {
            header("X-Admin-Token", "s3cret")
        }.body()
        assertTrue(after.devices.single().revoked)

        // неизвестное устройство → 404
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/admin/devices/nope?accountId=$accountId") { header("X-Admin-Token", "s3cret") }.status,
        )
    }

    @Test
    fun `admin activity logs metadata events behind the token`() = testApplication {
        val services = testServices(adminToken = "s3cret")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A"))
        }.body()
        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))))
        }

        // закрыто токеном
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/activity").status)

        val activity: AdminActivityResponse = client.get("/admin/activity") {
            header("X-Admin-Token", "s3cret")
        }.body()
        // свежее первым: push после register
        assertEquals(listOf("sync.push", "auth.register"), activity.events.map { it.event })
        val push = activity.events.first()
        assertEquals("devA", push.deviceId)
        assertTrue(push.detail.contains("1 records"))
        assertTrue(push.detail.contains("cursor 1"))
    }

    @Test
    fun `admin accounts and record envelopes are real and token-gated`() = testApplication {
        val services = testServices(adminToken = "s3cret")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A", platform = "Linux"))
        }.body()
        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(0xDE.toByte(), 0xAD.toByte()).b64()))))
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/accounts").status)

        val accounts: AdminAccountsResponse = client.get("/admin/accounts") { header("X-Admin-Token", "s3cret") }.body()
        val a = accounts.accounts.single()
        assertEquals(accountId, a.id)
        assertEquals(1, a.devices)
        assertEquals(1, a.activeDevices)
        assertEquals(1, a.records)
        assertEquals(0, a.tombstones)
        assertEquals(2L, a.storageBytes)

        // реальный envelope: настоящие байты шифротекста в hex-превью, без содержимого
        val records: AdminRecordsResponse = client.get("/admin/accounts/$accountId/records") {
            header("X-Admin-Token", "s3cret")
        }.body()
        val r = records.records.single()
        assertEquals("r1", r.id)
        assertEquals(2, r.blobBytes)
        assertEquals("de ad", r.previewHex)
    }

    @Test
    fun `admin purges tombstones and deletes account`() = testApplication {
        val services = testServices(adminToken = "s3cret")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A"))
        }.body()
        // создать запись и удалить её (tombstone); курсор устройства догоняет удаление
        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))))
        }
        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 2, "2026-06-29T00:00:01Z", "devA", true, byteArrayOf(1).b64()))))
        }
        // pull двигает курсор устройства до tip (serverSeq 2) — теперь purge безопасен
        client.get("/vault/records?since=0") { bearerAuth(tokens.accessToken) }

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/admin/accounts/$accountId/tombstones").status)
        val purge: AdminPurgeResponse = client.delete("/admin/accounts/$accountId/tombstones") {
            header("X-Admin-Token", "s3cret")
        }.body()
        assertEquals(1, purge.purged)

        // удаление аккаунта — под токеном, каскадом
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/admin/accounts/$accountId").status)
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/admin/accounts/$accountId") { header("X-Admin-Token", "s3cret") }.status,
        )
        val after: AdminAccountsResponse = client.get("/admin/accounts") { header("X-Admin-Token", "s3cret") }.body()
        assertTrue(after.accounts.isEmpty())
        // повторное удаление → 404
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/admin/accounts/$accountId") { header("X-Admin-Token", "s3cret") }.status,
        )
    }

    @Test
    fun `admin stats requires token, health is open`() = testApplication {
        val services = testServices(adminToken = "s3cret")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.OK, client.get("/admin/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/stats").status)

        // storageBytes = суммарный размер шифроблобов (LENGTH(blob)), считается на стороне БД
        val reg = srpRegister(accountId, password)
        val tokens: TokenResponse = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, reg.salt, reg.verifier, byteArrayOf(0).b64(), "devA", "Laptop A"))
        }.body()
        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1, 2, 3).b64()))))
        }
        val stats: StatsResponse = client.get("/admin/stats") { header("X-Admin-Token", "s3cret") }.body()
        assertEquals(1, stats.records)
        assertEquals(3L, stats.storageBytes)
    }
}
