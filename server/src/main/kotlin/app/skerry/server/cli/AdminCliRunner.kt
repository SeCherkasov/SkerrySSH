package app.skerry.server.cli

import app.skerry.server.model.AdminAccountsResponse
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminPurgeResponse
import app.skerry.server.model.AdminRecordsResponse
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.HealthResponse
import app.skerry.server.model.StatsResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `skerry-admin` — the administration CLI. It drives the very same `/admin` HTTP endpoints as the
 * web console, so there is one implementation and one authorization gate per operation; the CLI
 * only formats what the server returns.
 */

const val EXIT_OK = 0
const val EXIT_ERROR = 1
const val EXIT_USAGE = 2
const val EXIT_UNAUTHORIZED = 3
const val EXIT_NOT_FOUND = 4
const val EXIT_UNREACHABLE = 5

fun main(args: Array<String>) {
    val code = runBlocking { runCli(args.toList()) }
    exitProcess(code)
}

/**
 * Runs one CLI invocation and returns its exit code. Everything the process owns is injected so
 * the tests can drive the real routes over a test client: [clientFactory] builds the HTTP client,
 * [now] anchors relative timestamps, [out]/[err] capture output.
 */
suspend fun runCli(
    args: List<String>,
    env: Map<String, String> = System.getenv(),
    out: Appendable = System.out,
    err: Appendable = System.err,
    now: Long = System.currentTimeMillis(),
    clientFactory: (CliOptions) -> HttpClient = ::defaultHttpClient,
    readTokenFile: (String) -> String = { Files.readString(Path.of(it)).trim() },
): Int = when (val parsed = parseCli(args, env)) {
    is ParsedCli.Help -> {
        out.appendLine(parsed.text)
        EXIT_OK
    }
    is ParsedCli.Invalid -> {
        err.appendLine(parsed.message)
        err.appendLine("Run `skerry-admin --help` for usage.")
        EXIT_USAGE
    }
    is ParsedCli.Run -> {
        val token = resolveToken(parsed.options, readTokenFile, err)
        if (token is TokenResult.Failed) {
            EXIT_ERROR
        } else {
            warnAboutCleartext(parsed.options.baseUrl, err)
            execute(
                command = parsed.command,
                options = parsed.options,
                token = (token as TokenResult.Resolved).value,
                out = out,
                err = err,
                now = now,
                clientFactory = clientFactory,
            )
        }
    }
}

/**
 * A token sent over plain HTTP to anything but the local machine is on the wire in the clear. The
 * server itself is meant to sit behind a TLS proxy, so this is a misconfiguration worth one line —
 * not an error, since a LAN-only instance without TLS is a deliberate, documented choice.
 */
private fun warnAboutCleartext(baseUrl: String, err: Appendable) {
    if (!baseUrl.startsWith("http://", ignoreCase = true)) return
    val host = baseUrl.removePrefix("http://").substringBefore('/').substringBefore(':')
    if (host in LOOPBACK_HOSTS) return
    err.appendLine("warning: $baseUrl is plain HTTP — the admin token crosses the network in the clear")
}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

private sealed interface TokenResult {
    data class Resolved(val value: String?) : TokenResult
    data object Failed : TokenResult
}

private fun resolveToken(options: CliOptions, readTokenFile: (String) -> String, err: Appendable): TokenResult {
    if (options.token != null) return TokenResult.Resolved(options.token)
    val file = options.tokenFile ?: return TokenResult.Resolved(null)
    return runCatching { readTokenFile(file) }
        .fold(
            onSuccess = { TokenResult.Resolved(it.takeIf(String::isNotBlank)) },
            onFailure = {
                err.appendLine("cannot read the admin token from $file: ${it.message ?: it::class.simpleName}")
                TokenResult.Failed
            },
        )
}

/**
 * The client is not closed: the process exits right after one command, and tests inject their own.
 * A connection failure surfaces as one line, not a stack trace — a stopped server is the normal
 * case for a CLI, not a crash.
 */
private suspend fun execute(
    command: AdminCommand,
    options: CliOptions,
    token: String?,
    out: Appendable,
    err: Appendable,
    now: Long,
    clientFactory: (CliOptions) -> HttpClient,
): Int = try {
    val api = AdminApi(clientFactory(options), token)
    when (command) {
        AdminCommand.Health -> api.render(out, err, options.json, "/admin/health") { body ->
            val health = jsonCodec.decodeFromString<HealthResponse>(body)
            keyValues(listOf("Status" to health.status, "Version" to health.version))
        }
        AdminCommand.Stats -> api.render(out, err, options.json, "/admin/stats") { body ->
            val stats = jsonCodec.decodeFromString<StatsResponse>(body)
            keyValues(
                listOf(
                    "Accounts" to stats.accounts.toString(),
                    "Active devices" to stats.devices.toString(),
                    "Records" to stats.records.toString(),
                    "Pairing sessions" to stats.pairingSessions.toString(),
                    "Storage" to "${humanBytes(stats.storageBytes)} (${stats.storageBytes} bytes)",
                ),
            )
        }
        // Printed verbatim: the point is to see exactly what a scraper would get. Bearer, not the
        // admin header — /metrics has its own credential.
        AdminCommand.Metrics -> api.scrape(out, err, options.metricsToken)
        is AdminCommand.Activity -> api.render(out, err, options.json, "/admin/activity".withLimit(command.limit)) { body ->
            val response = jsonCodec.decodeFromString<AdminActivityResponse>(body)
            if (response.events.isEmpty()) return@render "no events in the audit log"
            table(
                listOf("WHEN", "ACCOUNT", "DEVICE", "EVENT", "DETAIL"),
                response.events.map {
                    listOf(utcMinutes(it.createdAt), it.accountId, it.deviceId ?: "—", it.event, it.detail)
                },
            ) + footer(response.events.size, response.total)
        }
        is AdminCommand.AccountsList -> api.render(out, err, options.json, "/admin/accounts".withLimit(command.limit)) { body ->
            val response = jsonCodec.decodeFromString<AdminAccountsResponse>(body)
            if (response.accounts.isEmpty()) return@render "no accounts on this server"
            table(
                listOf("ACCOUNT", "DEVICES", "RECORDS", "TOMBSTONES", "STORAGE", "LAST SEEN", "CREATED"),
                response.accounts.map {
                    listOf(
                        it.id,
                        "${it.activeDevices}/${it.devices}",
                        it.records.toString(),
                        it.tombstones.toString(),
                        humanBytes(it.storageBytes),
                        relativeTime(it.lastSeenAt, now),
                        utcMinutes(it.createdAt),
                    )
                },
            ) + footer(response.accounts.size, response.total)
        }
        is AdminCommand.AccountRecords -> {
            val path = "/admin/accounts/${command.accountId.encodeURLPathPart()}/records".withLimit(command.limit)
            api.render(out, err, options.json, path) { body ->
                val response = jsonCodec.decodeFromString<AdminRecordsResponse>(body)
                if (response.records.isEmpty()) {
                    "no records for ${response.accountId}"
                } else {
                    table(
                        listOf("RECORD", "TYPE", "VER", "BYTES", "STATE", "SEQ", "CIPHERTEXT"),
                        response.records.map {
                            listOf(
                                it.id,
                                it.type,
                                it.version.toString(),
                                it.blobBytes.toString(),
                                if (it.deleted) "tombstone" else "live",
                                it.serverSeq.toString(),
                                it.previewHex.take(23) + "…",
                            )
                        },
                    )
                }
            }
        }
        is AdminCommand.DevicesList -> {
            val path = "/admin/devices".withLimit(command.limit)
                .withParam("accountId", command.accountId)
            api.render(out, err, options.json, path) { body ->
                val response = jsonCodec.decodeFromString<AdminDevicesResponse>(body)
                if (response.devices.isEmpty()) return@render "no active devices"
                table(
                    listOf("ACCOUNT", "DEVICE", "NAME", "PLATFORM", "LAST SEEN", "CURSOR", "STATUS"),
                    response.devices.map {
                        listOf(
                            it.accountId,
                            it.id,
                            it.name,
                            it.platform ?: "—",
                            relativeTime(it.lastSeenAt, now),
                            it.syncVersion?.toString() ?: "—",
                            if (it.revoked) "revoked" else "active",
                        )
                    },
                ) + footer(response.devices.size, response.total)
            }
        }
        is AdminCommand.DeviceRevoke -> {
            val path = "/admin/devices/${command.deviceId.encodeURLPathPart()}"
                .withParam("accountId", command.accountId)
            api.act(
                out, err, options.json, path,
                done = "revoked ${command.deviceId} (${command.accountId})",
                missing = "no device ${command.deviceId} on account ${command.accountId}",
            )
        }
        is AdminCommand.AccountPurgeTombstones -> {
            val path = "/admin/accounts/${command.accountId.encodeURLPathPart()}/tombstones"
            api.delete(path).handle(out, err, options.json) { body ->
                "purged ${jsonCodec.decodeFromString<AdminPurgeResponse>(body).purged} tombstones"
            }
        }
        is AdminCommand.AccountDelete -> {
            val path = "/admin/accounts/${command.accountId.encodeURLPathPart()}"
            api.act(
                out, err, options.json, path,
                done = "deleted ${command.accountId} and all of its data",
                missing = "no account ${command.accountId} on this server",
            )
        }
    }
} catch (e: IOException) {
    err.appendLine("cannot reach ${options.baseUrl}: ${e.message ?: e::class.simpleName}")
    EXIT_UNREACHABLE
} catch (e: SerializationException) {
    // An older CLI against a newer server (or vice versa) must fail like every other error path —
    // one line — rather than dumping a stack trace at an operator.
    // kotlinx's own message spans several lines (it echoes the input); keep the first, so this path
    // stays the single line every other CLI error is.
    val detail = e.message?.lineSequence()?.firstOrNull()?.trim() ?: "could not decode the body"
    err.appendLine("unexpected response from ${options.baseUrl}: $detail")
    EXIT_ERROR
}

/** Ktor client for real runs: base URL from the options, no redirect following, short timeouts. */
private fun defaultHttpClient(options: CliOptions): HttpClient = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = false
    defaultRequest { url(options.baseUrl) }
}

private val jsonCodec = Json { ignoreUnknownKeys = true }

private class AdminApi(private val client: HttpClient, private val token: String?) {

    suspend fun get(path: String): HttpResponse = client.get(path) { adminToken() }

    /** GET /metrics with its own bearer token, printed exactly as a scraper would receive it. */
    suspend fun scrape(out: Appendable, err: Appendable, metricsToken: String?): Int {
        val response = client.get("/metrics") {
            metricsToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            err.appendLine("unauthorized: /metrics needs its own token (SKERRY_METRICS_TOKEN or --metrics-token)")
            return EXIT_UNAUTHORIZED
        }
        if (response.status == HttpStatusCode.NotFound) {
            err.appendLine("not found: metrics are disabled on this server (set SKERRY_METRICS=token)")
            return EXIT_NOT_FOUND
        }
        return response.handle(out, err, json = true) { it }
    }

    suspend fun delete(path: String): HttpResponse = client.delete(path) { adminToken() }

    private fun io.ktor.client.request.HttpRequestBuilder.adminToken() {
        token?.let { header("X-Admin-Token", it) }
    }

    /** GET [path] and print either the raw body (`--json`) or [format]ted output. */
    suspend fun render(
        out: Appendable,
        err: Appendable,
        json: Boolean,
        path: String,
        format: (String) -> String,
    ): Int = get(path).handle(out, err, json, format)

    /** DELETE [path] where success carries no body (204): print [done] or `{"ok":true}`. */
    suspend fun act(
        out: Appendable,
        err: Appendable,
        json: Boolean,
        path: String,
        done: String,
        missing: String,
    ): Int {
        val response = delete(path)
        return when {
            response.status == HttpStatusCode.NoContent -> {
                out.appendLine(if (json) """{"ok":true}""" else done)
                EXIT_OK
            }
            // These endpoints answer 404 with no body, so the server has no message to relay.
            response.status == HttpStatusCode.NotFound -> {
                err.appendLine("not found: $missing")
                EXIT_NOT_FOUND
            }
            else -> response.reportFailure(err)
        }
    }
}

private suspend fun HttpResponse.handle(
    out: Appendable,
    err: Appendable,
    json: Boolean,
    format: (String) -> String,
): Int {
    if (!status.isSuccess()) return reportFailure(err)
    val body = bodyAsText()
    out.appendLine(if (json) body.trimEnd() else format(body))
    return EXIT_OK
}

/**
 * Maps an HTTP failure onto an exit code a shell script can branch on, with the server's own error
 * message when it sent one.
 */
private suspend fun HttpResponse.reportFailure(err: Appendable): Int {
    val body = runCatching { bodyAsText() }.getOrDefault("")
    val message = runCatching { jsonCodec.decodeFromString<ErrorResponse>(body).error }
        .getOrElse { body.takeIf(String::isNotBlank)?.lines()?.first() ?: status.description }
    return when (status) {
        HttpStatusCode.Unauthorized -> {
            err.appendLine("unauthorized: $message (set SKERRY_ADMIN_TOKEN or pass --token)")
            EXIT_UNAUTHORIZED
        }
        HttpStatusCode.NotFound -> {
            err.appendLine("not found: $message")
            EXIT_NOT_FOUND
        }
        else -> {
            err.appendLine("server returned ${status.value}: $message")
            EXIT_ERROR
        }
    }
}

private fun String.withLimit(limit: Int?): String = withParam("limit", limit?.toString())

private fun String.withParam(name: String, value: String?): String = when {
    value == null -> this
    '?' in this -> "$this&$name=${value.encodeURLParameter()}"
    else -> "$this?$name=${value.encodeURLParameter()}"
}

private fun footer(shown: Int, total: Long): String =
    if (shown.toLong() >= total) "\n${shown} of $total" else "\n$shown of $total (raise --limit for more)"
