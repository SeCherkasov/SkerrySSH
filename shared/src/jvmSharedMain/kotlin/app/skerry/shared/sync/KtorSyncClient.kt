package app.skerry.shared.sync

import app.skerry.sync.wire.ChallengeRequest
import app.skerry.sync.wire.ChallengeResponse
import app.skerry.sync.wire.DevicesResponse
import app.skerry.sync.wire.KeysResponse
import app.skerry.sync.wire.PairingClaimRequest
import app.skerry.sync.wire.PairingClaimResponse
import app.skerry.sync.wire.PairingStartRequest
import app.skerry.sync.wire.PairingStartResponse
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.PushResponse
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import app.skerry.sync.wire.RefreshRequest
import app.skerry.sync.wire.RegisterRequest
import app.skerry.sync.wire.TokenResponse
import app.skerry.sync.wire.VerifyRequest
import app.skerry.sync.wire.VerifyResponse
import com.nimbusds.srp6.SRP6ClientSession
import com.nimbusds.srp6.SRP6CryptoParams
import com.nimbusds.srp6.SRP6Exception
import com.nimbusds.srp6.SRP6VerifierGenerator
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamSummary
import app.skerry.sync.wire.AccountKeyResponse
import app.skerry.sync.wire.PublishKeyRequest
import app.skerry.sync.wire.RekeyEnvelopeDto
import app.skerry.sync.wire.TeamActivityResponse
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TeamInviteRequest
import app.skerry.sync.wire.TeamMembersResponse
import app.skerry.sync.wire.TeamRekeyRequest
import app.skerry.sync.wire.TeamRoleChangeRequest
import app.skerry.sync.wire.TeamsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * JVM implementation of [SyncClient] (desktop + Android): Ktor CIO client + Nimbus SRP-6a.
 * Zero-knowledge: only the SRP verifier (derived from [authKey]) and encrypted blobs go to the
 * server; password/masterKey/dataKey stay on the device. SRP parameters: the 2048-bit RFC 5054
 * group, SHA-256 (matching the server).
 *
 * [serverUrl] — base HTTP(S) URL with no trailing slash, e.g. `https://sync.example.com`.
 */
class KtorSyncClient(
    private val serverUrl: String,
    private val http: HttpClient = defaultHttpClient(),
) : SyncClient, TeamClient {

    private val params: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")
    private val random = SecureRandom()

    override suspend fun register(
        accountId: String,
        authKey: ByteArray,
        wrappedDataKey: ByteArray,
        device: DeviceInfo,
    ): SyncSession {
        // The client itself computes the salt and verifier from authKey — the server never sees the password.
        val salt = BigInteger(256, random)
        val verifier = SRP6VerifierGenerator(params).generateVerifier(salt, accountId, authKey.toHex())
        val resp: TokenResponse = post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    accountId = accountId,
                    srpSalt = salt.toString(16),
                    srpVerifier = verifier.toString(16),
                    wrappedDataKey = wrappedDataKey.b64(),
                    deviceId = device.id,
                    deviceName = device.name,
                    platform = device.platform,
                ),
            )
        }.bodyChecked()
        return SyncSession(accountId, resp.accessToken, resp.refreshToken)
    }

    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession {
        val srp = SRP6ClientSession()
        srp.step1(accountId, authKey.toHex())

        val challenge: ChallengeResponse = post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(accountId))
        }.bodyChecked()

        val creds = srp.step2(params, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))
        val verify: VerifyResponse = post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                VerifyRequest(
                    challengeId = challenge.challengeId,
                    a = creds.A.toString(16),
                    m1 = creds.M1.toString(16),
                    deviceId = device.id,
                    deviceName = device.name,
                    platform = device.platform,
                ),
            )
        }.bodyChecked()

        // Verify the server's counter-proof M2: protection against a spoofed server / MITM.
        try {
            srp.step3(BigInteger(verify.m2, 16))
        } catch (e: SRP6Exception) {
            throw SyncException(SyncException.Kind.PROTOCOL, "server SRP proof (M2) invalid", e)
        }
        return SyncSession(accountId, verify.accessToken, verify.refreshToken)
    }

    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray {
        val resp: KeysResponse = get("/vault/keys") { bearerAuth(session.accessToken) }.bodyChecked()
        return resp.wrappedDataKey.unb64()
    }

    override suspend fun pull(session: SyncSession, since: Long): RecordPage {
        val resp: RecordsResponse = get("/vault/records?since=$since") {
            bearerAuth(session.accessToken)
        }.bodyChecked()
        return RecordPage(resp.records.map { it.toRemote() }, resp.cursor, resp.compactedIds)
    }

    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
        val resp: PushResponse = put("/vault/records") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(records.map { it.toWire() }))
        }.bodyChecked()
        return RecordPage(resp.records.map { it.toRemote() }, resp.cursor)
    }

    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> {
        val resp: DevicesResponse = get("/devices") { bearerAuth(session.accessToken) }.bodyChecked()
        return resp.devices.map { RemoteDevice(it.id, it.name, it.createdAt, it.lastSeenAt, it.revoked, it.current) }
    }

    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean {
        val resp = http.delete("$serverUrl/devices/$deviceId") { bearerAuth(session.accessToken) }
        return when (resp.status) {
            HttpStatusCode.NoContent -> true
            HttpStatusCode.NotFound -> false
            else -> throw resp.toException()
        }
    }

    override suspend fun refresh(session: SyncSession): SyncSession {
        val resp: TokenResponse = post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(session.refreshToken))
        }.bodyChecked()
        return SyncSession(session.accountId, resp.accessToken, resp.refreshToken)
    }

    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket {
        val resp: PairingStartResponse = post("/pairing/start") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(encryptedDataKey.b64()))
        }.bodyChecked()
        return PairingTicket(resp.code, resp.expiresAt)
    }

    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult {
        val resp: PairingClaimResponse = post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(code, device.id, device.name))
        }.bodyChecked()
        return PairingResult(
            accountId = resp.accountId,
            encryptedDataKey = resp.encryptedDataKey.unb64(),
            session = SyncSession(resp.accountId, resp.accessToken, resp.refreshToken),
        )
    }

    override fun changes(session: SyncSession): Flow<SyncSignal> = flow {
        val wsUrl = serverUrl.replaceFirst("http", "ws") + "/sync"
        http.webSocket(urlString = wsUrl, request = { bearerAuth(session.accessToken) }) {
            for (frame in incoming) {
                if (frame is Frame.Text) parseSignal(frame.readText())?.let { emit(it) }
            }
        }
    }

    // --- TeamClient ---

    override suspend fun publishKey(session: SyncSession, publicKey: ByteArray, signPublicKey: ByteArray) {
        put("/account/key") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PublishKeyRequest(publicKey.b64(), signPublicKey.b64()))
        }.expectSuccess()
    }

    override suspend fun fetchPublicKey(session: SyncSession, accountId: String): AccountKeys? {
        val resp = get("/account/keys/${accountId.encodeURLPathPart()}") { bearerAuth(session.accessToken) }
        if (resp.status == HttpStatusCode.NotFound) return null
        if (!resp.status.isSuccess()) throw resp.toException()
        val body = resp.body<AccountKeyResponse>()
        return AccountKeys(body.publicKey.unb64(), body.signPublicKey.unb64())
    }

    override suspend fun createTeam(session: SyncSession, teamId: String) {
        post("/teams") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest(teamId))
        }.expectSuccess()
    }

    override suspend fun listTeams(session: SyncSession): List<TeamSummary> {
        val resp: TeamsResponse = get("/teams") { bearerAuth(session.accessToken) }.bodyChecked()
        return resp.teams.map {
            TeamSummary(
                id = it.id,
                ownerAccountId = it.ownerAccountId,
                role = TeamRole.fromWire(it.role),
                status = TeamMemberStatus.fromWire(it.status),
                createdAt = it.createdAt,
                memberCount = it.memberCount,
                envelope = it.envelope?.unb64(),
                keyEpoch = it.keyEpoch,
                keyEnvelope = it.keyEnvelope?.unb64(),
            )
        }
    }

    override suspend fun members(session: SyncSession, teamId: String): List<TeamMember> {
        val resp: TeamMembersResponse = get("/teams/${teamId.encodeURLPathPart()}/members") {
            bearerAuth(session.accessToken)
        }.bodyChecked()
        return resp.members.map {
            TeamMember(it.accountId, TeamRole.fromWire(it.role), TeamMemberStatus.fromWire(it.status), it.createdAt)
        }
    }

    override suspend fun invite(session: SyncSession, teamId: String, accountId: String, role: TeamRole, envelope: ByteArray) {
        post("/teams/${teamId.encodeURLPathPart()}/members") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamInviteRequest(accountId, envelope.b64(), role.wire))
        }.expectSuccess()
    }

    override suspend fun accept(session: SyncSession, teamId: String) {
        post("/teams/${teamId.encodeURLPathPart()}/accept") { bearerAuth(session.accessToken) }.expectSuccess()
    }

    override suspend fun changeRole(session: SyncSession, teamId: String, accountId: String, role: TeamRole) {
        put("/teams/${teamId.encodeURLPathPart()}/members/${accountId.encodeURLPathPart()}/role") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamRoleChangeRequest(role.wire))
        }.expectSuccess()
    }

    override suspend fun rekey(session: SyncSession, teamId: String, newEpoch: Long, envelopes: Map<String, ByteArray>) {
        post("/teams/${teamId.encodeURLPathPart()}/rekey") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamRekeyRequest(newEpoch, envelopes.map { (id, env) -> RekeyEnvelopeDto(id, env.b64()) }))
        }.expectSuccess()
    }

    override suspend fun teamActivity(session: SyncSession, teamId: String): List<TeamActivityEntry> {
        val resp: TeamActivityResponse = get("/teams/${teamId.encodeURLPathPart()}/activity") {
            bearerAuth(session.accessToken)
        }.bodyChecked()
        return resp.entries.map { TeamActivityEntry(it.actorAccountId, it.event, it.detail, it.createdAt) }
    }

    override suspend fun removeMember(session: SyncSession, teamId: String, accountId: String) {
        request {
            http.delete("$serverUrl/teams/${teamId.encodeURLPathPart()}/members/${accountId.encodeURLPathPart()}") {
                bearerAuth(session.accessToken)
            }
        }.expectSuccess()
    }

    override suspend fun deleteTeam(session: SyncSession, teamId: String) {
        request {
            http.delete("$serverUrl/teams/${teamId.encodeURLPathPart()}") { bearerAuth(session.accessToken) }
        }.expectSuccess()
    }

    override suspend fun pullTeam(session: SyncSession, teamId: String, since: Long): RecordPage {
        val resp: RecordsResponse = get("/teams/${teamId.encodeURLPathPart()}/records?since=$since") {
            bearerAuth(session.accessToken)
        }.bodyChecked()
        return RecordPage(resp.records.map { it.toRemote() }, resp.cursor, resp.compactedIds)
    }

    override suspend fun pushTeam(session: SyncSession, teamId: String, records: List<RemoteRecord>): RecordPage {
        val resp: PushResponse = put("/teams/${teamId.encodeURLPathPart()}/records") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(records.map { it.toWire() }))
        }.bodyChecked()
        return RecordPage(resp.records.map { it.toRemote() }, resp.cursor)
    }

    override suspend fun ping(): Boolean = try {
        // Open liveness endpoint (see server Plugins.kt `/healthz`). No bearer token — the ping
        // must succeed even without a session (vault locked). Any failure means unreachable (don't throw).
        http.get("$serverUrl/healthz").status.isSuccess()
    } catch (e: CancellationException) {
        throw e // coroutine cancellation isn't "server unreachable" — the signal must reach the caller
    } catch (e: Exception) {
        false
    }

    override suspend fun close() = http.close()

    // --- helpers ---

    private suspend fun post(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        request { http.post("$serverUrl$path", block) }

    private suspend fun get(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        request { http.get("$serverUrl$path", block) }

    private suspend fun put(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        request { http.put("$serverUrl$path", block) }

    /** Wraps network failures in [SyncException] NETWORK (instead of a bare IOException escaping). */
    private suspend fun request(call: suspend () -> HttpResponse): HttpResponse = try {
        call()
    } catch (e: CancellationException) {
        throw e // coroutine cancellation isn't a network failure, must not be swallowed (would break structured concurrency)
    } catch (e: SyncException) {
        throw e
    } catch (e: Exception) {
        throw SyncException(SyncException.Kind.NETWORK, "network error: ${e.message}", e)
    }

    /** 2xx — ok (body not needed), otherwise [SyncException] by status. */
    private suspend fun HttpResponse.expectSuccess() {
        if (!status.isSuccess()) throw toException()
    }

    /**
     * WS `/sync` frame -> [SyncSignal]; an unrecognized format yields null (forward
     * compatibility: new frame types don't crash old clients).
     */
    private fun parseSignal(text: String): SyncSignal? {
        text.toLongOrNull()?.let { return SyncSignal.Account(it) }
        if (text == "teams") return SyncSignal.Membership
        if (text.startsWith("team:")) {
            val teamId = text.substringAfter("team:").substringBeforeLast(':')
            val cursor = text.substringAfterLast(':').toLongOrNull() ?: return null
            if (teamId.isEmpty() || teamId.contains(':')) return null
            return SyncSignal.Team(teamId, cursor)
        }
        return null
    }

    /** Parses the body on 2xx, otherwise throws [SyncException] by status. */
    private suspend inline fun <reified T> HttpResponse.bodyChecked(): T {
        if (!status.isSuccess()) throw toException()
        return body()
    }

    private suspend fun HttpResponse.toException(): SyncException {
        val kind = when (status) {
            HttpStatusCode.Unauthorized -> SyncException.Kind.UNAUTHORIZED
            HttpStatusCode.NotFound -> SyncException.Kind.NOT_FOUND
            HttpStatusCode.Conflict -> SyncException.Kind.CONFLICT
            HttpStatusCode.Gone -> SyncException.Kind.GONE
            else -> SyncException.Kind.PROTOCOL
        }
        return SyncException(kind, "server responded ${status.value}")
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    // Zero-knowledge limitation (like the String password in IonspinVaultCrypto.deriveMasterKey):
    // Nimbus SRP accepts the password only as a String, so the hex authKey inevitably lives as an
    // immutable string until GC — it can't be wiped. Lifetime is confined to the
    // step1/generateVerifier call; the caller wipes the authKey (ByteArray) itself. authKey is a
    // derived subkey (KDF from masterKey), not master key material.
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

    private fun RecordDto.toRemote() = RemoteRecord(id, type, version, updatedAt, deviceId, deleted, blob.unb64())
    private fun RemoteRecord.toWire() = RecordDto(id, type, version, updatedAt, deviceId, deleted, blob.b64())

    companion object {
        /**
         * Ping period for the `/sync` live-pull socket. CIO exempts WebSockets from its request
         * timeout, so without pings a connection that died with no FIN/RST (Wi-Fi switch, suspend,
         * NAT idle timeout) never errors: [changes] hangs on a dead socket while the status stays
         * Online and live-pull is silently gone. The pinger detects the dead peer (no pong within
         * ~2× the interval), fails the session, and the coordinator's watch loop reconnects with
         * backoff. 30s also keeps NAT/proxy idle timeouts (typically ≥60s) from dropping the mapping.
         */
        const val WS_PING_INTERVAL_MS = 30_000L

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets) { pingIntervalMillis = WS_PING_INTERVAL_MS }
        }
    }
}
