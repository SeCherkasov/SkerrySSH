package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.AdminAccountsResponse
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminPurgeResponse
import app.skerry.server.model.AdminRecordsResponse
import app.skerry.sync.wire.ChallengeRequest
import app.skerry.sync.wire.ChallengeResponse
import app.skerry.sync.wire.DevicesResponse
import app.skerry.sync.wire.KeysResponse
import app.skerry.sync.wire.PairingClaimRequest
import app.skerry.sync.wire.PairingClaimResponse
import app.skerry.sync.wire.PairingStartRequest
import app.skerry.sync.wire.PairingStartResponse
import app.skerry.sync.wire.PushResponse
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import app.skerry.server.model.StatsResponse
import app.skerry.sync.wire.TokenResponse
import app.skerry.sync.wire.VerifyRequest
import app.skerry.sync.wire.VerifyResponse
import app.skerry.server.model.b64
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutesTest {

    private val accountId = "alice@example.com"
    private val password = "auth-key-hex-abc123"

    @Test
    fun `register then push and pull round-trips encrypted record`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val wrapped = byteArrayOf(7, 7, 7)
        val tokens = client.registerAccount(accountId, password, wrappedDataKey = wrapped)

        // wrappedDataKey is returned unchanged
        val keys: KeysResponse = client.get("/vault/keys") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(wrapped.b64(), keys.wrappedDataKey)

        // push a ciphertext blob
        val blob = byteArrayOf(1, 2, 3, 4)
        val push: PushResponse = client
            .pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, blob.b64()))
            .body()
        assertEquals(1L, push.cursor)

        // delta since=0 returns the same record
        val delta: RecordsResponse = client.get("/vault/records?since=0") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(1, delta.records.size)
        assertEquals(blob.b64(), delta.records.single().blob)
        assertEquals(1L, delta.cursor)
    }

    @Test
    fun `no-op push does not publish a change notification`() = testApplication {
        // Publish a WS change signal only when the cursor actually advances, not on every PUT
        // (a same version+deviceId upsert is a no-op with wins=false).
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tokens = client.registerAccount(accountId, password)

        val record = RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64())
        kotlinx.coroutines.coroutineScope {
            // UNDISPATCHED so collect registers synchronously before the first push (avoids a subscription race).
            val published = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val watch = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                services.notifier.forAccount(accountId).collect { published.send(it) }
            }

            // First push is a real change (cursor 0->1): a signal must arrive.
            client.pushRecord(tokens.accessToken, record)
            assertEquals(1L, withTimeout(2_000) { published.receive() })

            // Repeat push of the same record is a no-op (wins=false, cursor stays 1): no signal.
            client.pushRecord(tokens.accessToken, record)
            delay(300)
            assertEquals(null, published.tryReceive().getOrNull())
            watch.cancel()
        }
    }

    @Test
    fun `push accepts TUNNEL and SETTINGS record types and rejects an unknown type`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tokens = client.registerAccount(accountId, password)

        // TUNNEL must be in the server's type whitelist, or tunnel records can't sync.
        val ok = client.pushRecord(
            tokens.accessToken,
            RecordDto("t1", "TUNNEL", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()),
        )
        assertEquals(HttpStatusCode.OK, ok.status)

        // SETTINGS (account-level "what to sync" record) must also be whitelisted, or the client
        // can't push it and selective sync breaks.
        val settings = client.pushRecord(
            tokens.accessToken,
            RecordDto("sync.settings", "SETTINGS", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()),
        )
        assertEquals(HttpStatusCode.OK, settings.status)

        // An arbitrary type is still rejected.
        val bad = client.pushRecord(
            tokens.accessToken,
            RecordDto("x1", "BOGUS", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()),
        )
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

        client.registerAccount(accountId, password, deviceName = "A")

        // correct password
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
        sc.step3(BigInteger(vr.m2, 16)) // not throwing means the server is authentic
        assertFalse(vr.reactivated, "a normal login of a live device is not a reactivation")

        // wrong password
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
        val tokens = client.registerAccount(accountId, password)

        val devices: DevicesResponse = client.get("/devices") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(1, devices.devices.size)
        assertTrue(devices.devices.single().current)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/devices/devA") { bearerAuth(tokens.accessToken) }.status,
        )
        // a revoked device no longer authenticates
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys") { bearerAuth(tokens.accessToken) }.status)
    }

    @Test
    fun `revoked device re-logs in with master password and regains access`() = testApplication {
        // Re-login with the correct master password (SRP) must reactivate a revoked device and
        // restore access; revoke kills current tokens but does not ban the device.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tokens = client.registerAccount(accountId, password)

        // revoke -> old token is dead
        client.delete("/devices/devA") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys") { bearerAuth(tokens.accessToken) }.status)
        // re-registering is impossible (account already exists); the client must log in instead
        val reReg = client.registerAccountResponse(accountId, password)
        assertEquals(HttpStatusCode.Conflict, reReg.status)

        // logging back in with the same device and correct password clears the revoke and issues fresh tokens
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

        // access restored: the new token works again, device no longer revoked
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(fresh.accessToken) }.status)
        val after: DevicesResponse = client.get("/devices") { bearerAuth(fresh.accessToken) }.body()
        assertEquals(false, after.devices.single { it.id == "devA" }.revoked)
        // The response flags the reactivation so the client rebuilds its vault from the server before pushing.
        assertTrue(fresh.reactivated, "re-login of a revoked device must be reported as a reactivation")
    }

    @Test
    fun `change password swaps verifier and wrap and revokes other devices`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val oldWrap = byteArrayOf(1, 1, 1)
        val newWrap = byteArrayOf(2, 2, 2)
        val newPassword = "new-auth-key-hex-xyz789"

        // devA registers; devB logs in via SRP so there is a second device to revoke.
        client.registerAccount(accountId, password, wrappedDataKey = oldWrap, deviceId = "devA")
        val devB = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(devB.accessToken) }.status)

        // devA rotates the password.
        val rotated = client.changePassword(accountId, password, newPassword, newWrap, deviceId = "devA")
        assertEquals(HttpStatusCode.OK, rotated.status)
        val body: app.skerry.sync.wire.ChangePasswordResponse = rotated.body()
        // The acting device's fresh tokens work, and the wrapped dataKey is now the new one.
        val keys: KeysResponse = client.get("/vault/keys") { bearerAuth(body.accessToken) }.body()
        assertEquals(newWrap.b64(), keys.wrappedDataKey)

        // The old password no longer logs in; the new one does.
        assertEquals(HttpStatusCode.Unauthorized, client.srpLoginResponse(accountId, password, "devC", "C").status)
        assertEquals(HttpStatusCode.OK, client.srpLoginResponse(accountId, newPassword, "devC", "C").status)

        // devB was revoked by the rotation: its old token is dead until it re-authenticates.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys") { bearerAuth(devB.accessToken) }.status)
    }

    @Test
    fun `change password with wrong current password is rejected and changes nothing`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val oldWrap = byteArrayOf(1, 1, 1)
        val newWrap = byteArrayOf(2, 2, 2)
        client.registerAccount(accountId, password, wrappedDataKey = oldWrap, deviceId = "devA")

        // A rotate proven with the WRONG current password must be refused.
        val rotated = client.changePassword(accountId, "wrong-password", "new-password", newWrap, deviceId = "devA")
        assertEquals(HttpStatusCode.Unauthorized, rotated.status)

        // Nothing changed: the original password still logs in and the wrap is untouched.
        val ok = client.srpLogin(accountId, password, deviceId = "devA", deviceName = "Laptop A")
        val keys: KeysResponse = client.get("/vault/keys") { bearerAuth(ok.accessToken) }.body()
        assertEquals(oldWrap.b64(), keys.wrappedDataKey)
    }

    @Test
    fun `pairing transfers encrypted data key to a new device`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password, deviceName = "A")

        val transferred = byteArrayOf(9, 9, 9) // dataKey encrypted with transferKey; the server never sees the key
        val start: PairingStartResponse = client.post("/pairing/start") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(transferred.b64()))
        }.body()

        // new device claims by code without logging in
        val claim: PairingClaimResponse = client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, "devB", "Phone B"))
        }.body()
        assertEquals(accountId, claim.accountId)
        assertEquals(transferred.b64(), claim.encryptedDataKey)

        // the issued token already works for device B
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(claim.accessToken) }.status)

        // the code is single-use
        assertEquals(
            HttpStatusCode.Gone,
            client.post("/pairing/claim") {
                contentType(ContentType.Application.Json)
                setBody(PairingClaimRequest(start.code, "devC", "C"))
            }.status,
        )
    }

    @Test
    fun `pairing claim rejects overlong identifiers with 400 without burning the code`() = testApplication {
        // Validation must run before consuming the code and return 400, or an oversized deviceId
        // would fail the insert after the code is already burned.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)

        val start: PairingStartResponse = client.post("/pairing/start") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(byteArrayOf(9).b64()))
        }.body()

        val longDevice = client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, "d".repeat(129), "Phone B"))
        }
        assertEquals(HttpStatusCode.BadRequest, longDevice.status)

        val longCode = client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest("c".repeat(129), "devB", "Phone B"))
        }
        assertEquals(HttpStatusCode.BadRequest, longCode.status)

        // The code is not burned by invalid attempts: a normal claim still succeeds.
        val claim: PairingClaimResponse = client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, "devB", "Phone B"))
        }.body()
        assertEquals(accountId, claim.accountId)
    }

    @Test
    fun `admin lists devices across accounts and revokes by id`() = testApplication {
        val services = testServices(adminToken = "s3cret")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password, platform = "Linux")
        // push advances the device cursor (syncVersion), exposed as console metadata
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))

        // the list is gated by the admin token
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

        // revoke is also token-gated
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/admin/devices/devA?accountId=$accountId").status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/admin/devices/devA?accountId=$accountId") { header("X-Admin-Token", "s3cret") }.status,
        )

        // after revoke the device is hidden from the admin list (revoked devices are inert, so the
        // list — and the "N of M" total — count only active devices)
        val after: AdminDevicesResponse = client.get("/admin/devices") {
            header("X-Admin-Token", "s3cret")
        }.body()
        assertTrue(after.devices.isEmpty())
        assertEquals(0, after.total)

        // unknown device -> 404
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
        val tokens = client.registerAccount(accountId, password)
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))

        // gated by the token
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/activity").status)

        val activity: AdminActivityResponse = client.get("/admin/activity") {
            header("X-Admin-Token", "s3cret")
        }.body()
        // newest first: push after register
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
        val tokens = client.registerAccount(accountId, password, platform = "Linux")
        client.pushRecord(
            tokens.accessToken,
            RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(0xDE.toByte(), 0xAD.toByte()).b64()),
        )

        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/accounts").status)

        val accounts: AdminAccountsResponse = client.get("/admin/accounts") { header("X-Admin-Token", "s3cret") }.body()
        val a = accounts.accounts.single()
        assertEquals(accountId, a.id)
        assertEquals(1, a.devices)
        assertEquals(1, a.activeDevices)
        assertEquals(1, a.records)
        assertEquals(0, a.tombstones)
        assertEquals(2L, a.storageBytes)

        // a real envelope: actual ciphertext bytes in the hex preview, no plaintext content
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
        val tokens = client.registerAccount(accountId, password)
        // create a record and delete it (tombstone); the device cursor catches up to the deletion
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1).b64()))
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 2, "2026-06-29T00:00:01Z", "devA", true, byteArrayOf(1).b64()))
        // pull advances the device cursor to tip (serverSeq 2), so purge is now safe
        client.get("/vault/records?since=0") { bearerAuth(tokens.accessToken) }

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/admin/accounts/$accountId/tombstones").status)
        val purge: AdminPurgeResponse = client.delete("/admin/accounts/$accountId/tombstones") {
            header("X-Admin-Token", "s3cret")
        }.body()
        assertEquals(1, purge.purged)

        // account deletion is token-gated and cascades
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/admin/accounts/$accountId").status)
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/admin/accounts/$accountId") { header("X-Admin-Token", "s3cret") }.status,
        )
        val after: AdminAccountsResponse = client.get("/admin/accounts") { header("X-Admin-Token", "s3cret") }.body()
        assertTrue(after.accounts.isEmpty())
        // deleting again -> 404
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

        // storageBytes is the total ciphertext blob size (LENGTH(blob)), computed in the DB
        val tokens = client.registerAccount(accountId, password)
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-06-29T00:00:00Z", "devA", false, byteArrayOf(1, 2, 3).b64()))
        val stats: StatsResponse = client.get("/admin/stats") { header("X-Admin-Token", "s3cret") }.body()
        assertEquals(1, stats.records)
        assertEquals(3L, stats.storageBytes)
    }
}
