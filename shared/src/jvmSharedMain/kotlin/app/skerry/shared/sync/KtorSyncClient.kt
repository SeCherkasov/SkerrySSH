package app.skerry.shared.sync

import com.nimbusds.srp6.SRP6ClientSession
import com.nimbusds.srp6.SRP6CryptoParams
import com.nimbusds.srp6.SRP6Exception
import com.nimbusds.srp6.SRP6VerifierGenerator
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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * JVM-реализация [SyncClient] (desktop + Android): Ktor CIO client + Nimbus SRP-6a. Zero-knowledge:
 * на сервер уходят только SRP-верификатор (из [authKey]) и шифроблобы; пароль/masterKey/dataKey
 * остаются на устройстве. SRP-параметры — 2048-битная группа RFC 5054, SHA-256 (как на сервере).
 *
 * [serverUrl] — базовый HTTP(S) URL без хвостового слэша, напр. `https://sync.example.com`.
 */
class KtorSyncClient(
    private val serverUrl: String,
    private val http: HttpClient = defaultHttpClient(),
) : SyncClient {

    private val params: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")
    private val random = SecureRandom()

    override suspend fun register(
        accountId: String,
        authKey: ByteArray,
        wrappedDataKey: ByteArray,
        device: DeviceInfo,
    ): SyncSession {
        // Клиент сам считает соль и верификатор из authKey — сервер пароля не видит.
        val salt = BigInteger(256, random)
        val verifier = SRP6VerifierGenerator(params).generateVerifier(salt, accountId, authKey.toHex())
        val resp: TokenResponseWire = post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequestWire(
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

        val challenge: ChallengeResponseWire = post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequestWire(accountId))
        }.bodyChecked()

        val creds = srp.step2(params, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))
        val verify: VerifyResponseWire = post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                VerifyRequestWire(
                    challengeId = challenge.challengeId,
                    a = creds.A.toString(16),
                    m1 = creds.M1.toString(16),
                    deviceId = device.id,
                    deviceName = device.name,
                    platform = device.platform,
                ),
            )
        }.bodyChecked()

        // Проверяем встречное доказательство сервера M2: защита от поддельного сервера/MITM.
        try {
            srp.step3(BigInteger(verify.m2, 16))
        } catch (e: SRP6Exception) {
            throw SyncException(SyncException.Kind.PROTOCOL, "server SRP proof (M2) invalid", e)
        }
        return SyncSession(accountId, verify.accessToken, verify.refreshToken)
    }

    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray {
        val resp: KeysResponseWire = get("/vault/keys") { bearerAuth(session.accessToken) }.bodyChecked()
        return resp.wrappedDataKey.unb64()
    }

    override suspend fun pull(session: SyncSession, since: Long): RecordPage {
        val resp: RecordsResponseWire = get("/vault/records?since=$since") {
            bearerAuth(session.accessToken)
        }.bodyChecked()
        return RecordPage(resp.records.map { it.toRemote() }, resp.cursor)
    }

    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
        val resp: PushResponseWire = put("/vault/records") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequestWire(records.map { it.toWire() }))
        }.bodyChecked()
        return RecordPage(resp.records.map { it.toRemote() }, resp.cursor)
    }

    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> {
        val resp: DevicesResponseWire = get("/devices") { bearerAuth(session.accessToken) }.bodyChecked()
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
        val resp: TokenResponseWire = post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequestWire(session.refreshToken))
        }.bodyChecked()
        return SyncSession(session.accountId, resp.accessToken, resp.refreshToken)
    }

    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket {
        val resp: PairingStartResponseWire = post("/pairing/start") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequestWire(encryptedDataKey.b64()))
        }.bodyChecked()
        return PairingTicket(resp.code, resp.expiresAt)
    }

    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult {
        val resp: PairingClaimResponseWire = post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequestWire(code, device.id, device.name))
        }.bodyChecked()
        return PairingResult(
            accountId = resp.accountId,
            encryptedDataKey = resp.encryptedDataKey.unb64(),
            session = SyncSession(resp.accountId, resp.accessToken, resp.refreshToken),
        )
    }

    override fun changes(session: SyncSession): Flow<Long> = flow {
        val wsUrl = serverUrl.replaceFirst("http", "ws") + "/sync"
        http.webSocket(urlString = wsUrl, request = { bearerAuth(session.accessToken) }) {
            for (frame in incoming) {
                if (frame is Frame.Text) frame.readText().toLongOrNull()?.let { emit(it) }
            }
        }
    }

    override suspend fun close() = http.close()

    // --- helpers ---

    private suspend fun post(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        request { http.post("$serverUrl$path", block) }

    private suspend fun get(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        request { http.get("$serverUrl$path", block) }

    private suspend fun put(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        request { http.put("$serverUrl$path", block) }

    /** Оборачивает сетевые сбои в [SyncException] NETWORK (вместо «голого» IOException наружу). */
    private suspend fun request(call: suspend () -> HttpResponse): HttpResponse = try {
        call()
    } catch (e: SyncException) {
        throw e
    } catch (e: Exception) {
        throw SyncException(SyncException.Kind.NETWORK, "network error: ${e.message}", e)
    }

    /** Парсит тело при 2xx, иначе бросает [SyncException] по статусу. */
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

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

    private fun RecordDtoWire.toRemote() = RemoteRecord(id, type, version, updatedAt, deviceId, deleted, blob.unb64())
    private fun RemoteRecord.toWire() = RecordDtoWire(id, type, version, updatedAt, deviceId, deleted, blob.b64())

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets)
        }
    }
}
