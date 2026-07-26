package app.skerry.server.cli

import app.skerry.server.Services
import app.skerry.server.configureServer
import app.skerry.server.routes.registerAccount
import app.skerry.server.routes.testServices
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI end to end against a real server: it must exercise the same `/admin` routes the console
 * uses, so a broken auth gate or a renamed field fails here rather than in production.
 */
class AdminCliRunnerTest {

    private class CliRun(val exitCode: Int, val out: String, val err: String)

    private suspend fun ApplicationTestBuilder.cli(
        vararg args: String,
        token: String? = ADMIN_TOKEN,
        url: String? = null,
        client: HttpClient = createClient { install(ContentNegotiation) { json() } },
    ): CliRun {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap {
            if (token != null) put("SKERRY_ADMIN_TOKEN", token)
            if (url != null) put("SKERRY_ADMIN_URL", url)
        }
        val code = runCli(
            args = args.toList(),
            env = env,
            out = out,
            err = err,
            now = NOW,
            clientFactory = { client },
        )
        return CliRun(code, out.toString(), err.toString())
    }

    /** The docker/k8s secret path: the token comes from a file, never from argv. */
    @Test
    fun `token file is read and a missing one is an error`() = withServer {
        seedAccount()
        val secret = Files.createTempFile("skerry-admin-token", "")
        Files.writeString(secret, "$ADMIN_TOKEN\n") // trailing newline as `echo` would leave it
        secret.toFile().deleteOnExit()

        val ok = cli("stats", "--token-file", secret.toString(), token = null)
        assertEquals(EXIT_OK, ok.exitCode, ok.err)

        val missing = cli("stats", "--token-file", "/nonexistent/skerry-token", token = null)
        assertEquals(EXIT_ERROR, missing.exitCode)
        assertTrue("/nonexistent/skerry-token" in missing.err, missing.err)
        // One line, no stack trace, and no echo of file contents.
        assertEquals(1, missing.err.lines().count { it.isNotBlank() }, missing.err)
    }

    /** Any other server-side failure is exit 1, distinct from unauthorized/not-found/unreachable. */
    @Test
    fun `a server error exits with the generic error code`() {
        val body = """{"error":"internal error"}"""
        val failing = HttpClient(MockEngine { respond(body, HttpStatusCode.InternalServerError) })
        val out = StringBuilder()
        val err = StringBuilder()
        val code = runBlocking {
            runCli(
                args = listOf("stats"),
                env = mapOf("SKERRY_ADMIN_TOKEN" to ADMIN_TOKEN),
                out = out,
                err = err,
                now = NOW,
                clientFactory = { failing },
            )
        }
        assertEquals(EXIT_ERROR, code)
        assertTrue("500" in err.toString() && "internal error" in err.toString(), err.toString())
    }

    private suspend fun ApplicationTestBuilder.seedAccount(accountId: String = "alice@example.com") {
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(accountId, "correct horse", deviceId = "devA", deviceName = "Laptop A", platform = "linux")
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(Services) -> Unit) = testApplication {
        val services = testServices(adminToken = ADMIN_TOKEN)
        application { configureServer(services) }
        block(services)
    }

    @Test
    fun `stats prints instance totals`() = withServer {
        seedAccount()
        val run = cli("stats")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue("Accounts" in run.out, run.out)
        assertTrue("1" in run.out, run.out)
    }

    @Test
    fun `json flag prints the raw server payload`() = withServer {
        seedAccount()
        val run = cli("stats", "--json")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        val parsed = Json.parseToJsonElement(run.out).jsonObject
        assertEquals("1", parsed["accounts"]?.jsonPrimitive?.content)
    }

    @Test
    fun `devices list shows the registered device`() = withServer {
        seedAccount()
        val run = cli("devices", "list")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue("devA" in run.out, run.out)
        assertTrue("alice@example.com" in run.out, run.out)
        assertTrue("linux" in run.out, run.out)
    }

    @Test
    fun `devices list can filter by account`() = withServer {
        seedAccount("alice@example.com")
        seedAccount("bob@example.com")
        val run = cli("devices", "list", "--account", "alice@example.com")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue("alice@example.com" in run.out, run.out)
        assertFalse("bob@example.com" in run.out, run.out)
    }

    @Test
    fun `device revoke takes effect on the server`() = withServer { services ->
        seedAccount()
        val run = cli("devices", "revoke", "devA", "--account", "alice@example.com")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue(services.devices.isRevoked("alice@example.com", "devA"))
    }

    @Test
    fun `revoking an unknown device reports not found`() = withServer {
        seedAccount()
        val run = cli("devices", "revoke", "ghost", "--account", "alice@example.com")
        assertEquals(EXIT_NOT_FOUND, run.exitCode)
        // The server answers 404 with no body here, so the message has to come from the CLI's own
        // context — "not found: Not Found" tells the operator nothing.
        assertTrue("ghost" in run.err && "alice@example.com" in run.err, run.err)
    }

    @Test
    fun `empty lists say so instead of printing a bare header`() = withServer {
        val devices = cli("devices", "list")
        assertEquals(EXIT_OK, devices.exitCode, devices.err)
        assertFalse("ACCOUNT" in devices.out, devices.out)
        assertTrue("no active devices" in devices.out, devices.out)

        val accounts = cli("accounts", "list")
        assertTrue("no accounts" in accounts.out, accounts.out)

        val activity = cli("activity")
        assertTrue("no events" in activity.out, activity.out)
    }

    @Test
    fun `accounts list and records expose metadata only`() = withServer {
        seedAccount()
        val list = cli("accounts", "list")
        assertEquals(EXIT_OK, list.exitCode, list.err)
        assertTrue("alice@example.com" in list.out, list.out)

        val records = cli("accounts", "records", "alice@example.com")
        assertEquals(EXIT_OK, records.exitCode, records.err)
    }

    @Test
    fun `account delete removes the account`() = withServer { services ->
        seedAccount()
        val run = cli("accounts", "delete", "alice@example.com", "--yes")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertEquals(0, services.admin.accountCount().toInt())
    }

    @Test
    fun `deleting an unknown account reports not found`() = withServer {
        val run = cli("accounts", "delete", "nobody@example.com", "--yes")
        assertEquals(EXIT_NOT_FOUND, run.exitCode)
    }

    @Test
    fun `purge tombstones reports a count`() = withServer {
        seedAccount()
        val run = cli("accounts", "purge-tombstones", "alice@example.com")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue("0" in run.out, run.out)
    }

    @Test
    fun `activity prints audit events`() = withServer {
        seedAccount()
        val run = cli("activity", "--limit", "5")
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue("alice@example.com" in run.out, run.out)
    }

    /** The scrape endpoint takes a bearer token of its own; the admin token must not be sent there. */
    @Test
    fun `metrics prints the exposition using the metrics token`() = testApplication {
        val services = testServices(
            adminToken = ADMIN_TOKEN,
            extraEnv = mapOf("SKERRY_METRICS" to "token", "SKERRY_METRICS_TOKEN" to "scrape-me"),
        )
        application { configureServer(services) }

        val denied = cli("metrics")
        assertEquals(EXIT_UNAUTHORIZED, denied.exitCode)
        assertTrue("SKERRY_METRICS_TOKEN" in denied.err, denied.err)

        val ok = cli("metrics", "--metrics-token", "scrape-me")
        assertEquals(EXIT_OK, ok.exitCode, ok.err)
        assertTrue("skerry_build_info" in ok.out, ok.out.take(200))
    }

    /** A token on the wire in the clear is worth a word; a loopback URL is not. */
    @Test
    fun `a remote plain-http url is called out`() = withServer {
        val remote = cli("stats", url = "http://sync.example.com")
        assertTrue("plain HTTP" in remote.err, remote.err)

        val local = cli("stats", url = "http://127.0.0.1:8080")
        assertFalse("plain HTTP" in local.err, local.err)

        val tls = cli("stats", url = "https://sync.example.com")
        assertFalse("plain HTTP" in tls.err, tls.err)
    }

    @Test
    fun `health needs no token`() = withServer {
        val run = cli("health", token = null)
        assertEquals(EXIT_OK, run.exitCode, run.err)
        assertTrue("ok" in run.out.lowercase(), run.out)
    }

    @Test
    fun `a missing token fails with the unauthorized exit code`() = withServer {
        seedAccount()
        val run = cli("stats", token = null)
        assertEquals(EXIT_UNAUTHORIZED, run.exitCode)
        assertTrue("token" in run.err.lowercase(), run.err)
    }

    @Test
    fun `a wrong token fails with the unauthorized exit code`() = withServer {
        val run = cli("stats", token = "nope")
        assertEquals(EXIT_UNAUTHORIZED, run.exitCode)
    }

    @Test
    fun `usage errors exit with the usage code and print to stderr`() = withServer {
        val run = cli("accounts", "delete", "alice@example.com")
        assertEquals(EXIT_USAGE, run.exitCode)
        assertTrue("--yes" in run.err, run.err)
        assertTrue(run.out.isEmpty(), run.out)
    }

    @Test
    fun `help goes to stdout and exits ok`() = withServer {
        val run = cli("--help")
        assertEquals(EXIT_OK, run.exitCode)
        assertTrue("skerry-admin" in run.out, run.out)
    }

    /**
     * A body the CLI cannot decode (an old CLI against a newer server) must read like every other
     * error path — one line — instead of a stack trace.
     */
    @Test
    fun `an undecodable response is reported in one line`() {
        val garbage = HttpClient(MockEngine { respond("{ not json", HttpStatusCode.OK) })
        val out = StringBuilder()
        val err = StringBuilder()
        val code = runBlocking {
            runCli(
                args = listOf("stats"),
                env = mapOf("SKERRY_ADMIN_TOKEN" to ADMIN_TOKEN),
                out = out,
                err = err,
                now = NOW,
                clientFactory = { garbage },
            )
        }
        assertEquals(EXIT_ERROR, code)
        assertEquals(1, err.lines().count { it.isNotBlank() }, err.toString())
        assertTrue("unexpected response" in err.toString(), err.toString())
    }

    /** A stopped server is the common case for a CLI; it must say so rather than dump a stack trace. */
    @Test
    fun `an unreachable server exits with the unreachable code`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = runBlocking {
            runCli(
                args = listOf("stats"),
                env = mapOf("SKERRY_ADMIN_TOKEN" to ADMIN_TOKEN),
                out = out,
                err = err,
                now = NOW,
                clientFactory = { throw IOException("connection refused") },
            )
        }
        assertEquals(EXIT_UNREACHABLE, code)
        assertTrue(err.isNotBlank())
        assertFalse("Exception" in err.toString(), err.toString())
    }

    private companion object {
        const val ADMIN_TOKEN = "admin-secret"
        const val NOW = 1_800_000_000_000L
    }
}
