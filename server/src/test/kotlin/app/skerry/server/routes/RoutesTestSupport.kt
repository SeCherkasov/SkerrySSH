package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.config.ServerConfig
import app.skerry.server.db.Db
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RegisterRequest
import app.skerry.sync.wire.TokenResponse
import app.skerry.server.model.b64
import com.nimbusds.srp6.SRP6ClientSession
import com.nimbusds.srp6.SRP6CryptoParams
import com.nimbusds.srp6.SRP6VerifierGenerator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.Database
import java.math.BigInteger
import java.nio.file.Files
import java.security.SecureRandom

val SRP_PARAMS: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")

fun testServices(adminToken: String = "", extraEnv: Map<String, String> = emptyMap()): Services {
    val file = Files.createTempFile("skerry-routes-", ".db")
    file.toFile().deleteOnExit()
    val config = ServerConfig.fromEnv(
        mapOf("SKERRY_DB_URL" to "jdbc:sqlite:${file.toAbsolutePath()}", "SKERRY_ADMIN_TOKEN" to adminToken) + extraEnv,
    )
    val database: Database = Db.connect(config)
    return Services(config, database)
}

/** SRP registration material, as the client would compute it before /auth/register. */
data class SrpRegistration(val salt: String, val verifier: String)

fun srpRegister(accountId: String, password: String): SrpRegistration {
    val salt = BigInteger(256, SecureRandom())
    val verifier = SRP6VerifierGenerator(SRP_PARAMS).generateVerifier(salt, accountId, password)
    return SrpRegistration(salt.toString(16), verifier.toString(16))
}

/** Client-side SRP session for login tests: derives A and M1 from the server's challenge. */
fun srpClient(accountId: String, password: String): SRP6ClientSession =
    SRP6ClientSession().apply { step1(accountId, password) }

/** Raw POST /auth/register (SRP material computed as on the client), for checking status codes. */
suspend fun HttpClient.registerAccountResponse(
    accountId: String,
    password: String,
    wrappedDataKey: ByteArray = byteArrayOf(0),
    deviceId: String = "devA",
    deviceName: String = "Laptop A",
    platform: String? = null,
): HttpResponse {
    val reg = srpRegister(accountId, password)
    return post("/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(accountId, reg.salt, reg.verifier, wrappedDataKey.b64(), deviceId, deviceName, platform))
    }
}

/** Registers an account+device as the client does; returns the token pair. */
suspend fun HttpClient.registerAccount(
    accountId: String,
    password: String,
    wrappedDataKey: ByteArray = byteArrayOf(0),
    deviceId: String = "devA",
    deviceName: String = "Laptop A",
    platform: String? = null,
): TokenResponse =
    registerAccountResponse(accountId, password, wrappedDataKey, deviceId, deviceName, platform).body()

/** PUT /vault/records with a single record, under a bearer token. */
suspend fun HttpClient.pushRecord(token: String, record: RecordDto): HttpResponse =
    put("/vault/records") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(PushRequest(listOf(record)))
    }
