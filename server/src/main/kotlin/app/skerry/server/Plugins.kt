package app.skerry.server

import app.skerry.server.auth.TokenService
import app.skerry.server.config.MetricsExposure
import app.skerry.server.metrics.HTTP_REQUESTS_METRIC
import app.skerry.server.metrics.JwtRejection
import app.skerry.server.metrics.RejectReason
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.ReadyResponse
import app.skerry.server.routes.adminRoutes
import app.skerry.server.routes.authRoutes
import app.skerry.server.routes.deviceRoutes
import app.skerry.server.routes.metricsRoutes
import app.skerry.server.routes.pairingClaimRoute
import app.skerry.server.routes.pairingStartRoute
import app.skerry.server.routes.shareRoutes
import app.skerry.server.routes.syncWebSocket
import app.skerry.server.routes.teamRoutes
import app.skerry.server.routes.teamScopeRoutes
import app.skerry.server.routes.vaultRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Paths excluded from [CallLogging]: machine polling, one line per call, nothing to learn from it. */
private val UNLOGGED_PATHS = setOf("/healthz", "/readyz", "/metrics")

/** Rate-limit bucket names (keyed by remote IP). Declared here, used by the routes. */
object RateLimits {
    val REGISTER = RateLimitName("auth-register")
    val SRP_CHALLENGE = RateLimitName("srp-challenge")
    val SRP_VERIFY = RateLimitName("srp-verify")
    val PAIRING_CLAIM = RateLimitName("pairing-claim")
    val REFRESH = RateLimitName("auth-refresh")
    val CHANGE_PASSWORD = RateLimitName("auth-change-password")
    val ADMIN = RateLimitName("admin")
    val TEAM_SESSION_EVENTS = RateLimitName("team-session-events")
    val METRICS = RateLimitName("metrics")
}

/**
 * Server version for /admin/health and the admin console. Stamped into version.properties by
 * Gradle (processResources) from the version in build.gradle.kts — the single bump point.
 */
val SERVER_VERSION: String = RateLimits::class.java.getResource("/version.properties")
    ?.readText()?.substringAfter("version=")?.trim()
    ?: error("version.properties missing from server resources")

val JWTPrincipal.accountId: String get() = payload.subject
val JWTPrincipal.deviceId: String get() = payload.getClaim(TokenService.CLAIM_DEVICE).asString()

/**
 * Principal for a route under `authenticate("auth-jwt")`. Throws an explicit error instead of
 * `!!` so moving a route out from under `authenticate {}` fails loudly instead of NPEing silently.
 */
fun ApplicationCall.jwtPrincipal(): JWTPrincipal =
    principal<JWTPrincipal>() ?: error("missing JWT principal — route must be under authenticate(\"auth-jwt\")")

/**
 * Installs plugins and routes. Split out of [module] so tests can start a server against a
 * test DB via `testApplication { application { configureServer(services) } }`.
 */
fun Application.configureServer(services: Services) {
    // Forward-compat: ignore unknown JSON fields (old client vs. new server). Field typos go
    // undetected as a tradeoff for a versionable API.
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    // The /sync protocol is server-push only: a legitimate client never sends payload frames,
    // so a tiny frame cap (close code 1009 on violation) stops an authenticated device from
    // buffering an arbitrarily large frame into server memory — the HTTP body limit below does
    // not apply to WebSocket traffic. The timeout reaps dead peers that stop answering pings.
    install(WebSockets) {
        pingPeriodMillis = 30_000
        timeoutMillis = 15_000
        maxFrameSize = 4096
    }
    // A 15s scrape plus the container healthcheck plus every client's ping() would be thousands of
    // INFO lines a day about nothing. Neither endpoint carries anything worth logging per call.
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path() !in UNLOGGED_PATHS }
    }
    // HTTP metrics. `distinctNotRegisteredRoutes = false` collapses every unmatched request into one
    // series: without it an unauthenticated GET /<random> loop would add a series per request and grow
    // the registry until the process dies.
    if (services.config.metrics != MetricsExposure.OFF) {
        install(MicrometerMetrics) {
            // The plugin's config constructor already built a LoggingMeterRegistry as its default,
            // and that registry starts a publishing thread — left alone it keeps dumping every meter
            // into the log once a minute. Close it as we swap ours in.
            val unusedDefault = registry
            registry = services.metrics.registry
            unusedDefault.close()
            metricName = HTTP_REQUESTS_METRIC
            distinctNotRegisteredRoutes = false
            // Our own MeterFilter defines the buckets (see ServerMetrics); letting the plugin register
            // its distribution config would add a filter after meters already exist, which Micrometer
            // warns about — and would fight over the same timer.
            registerDistributionStatisticConfig = false
            // ServerMetrics owns the JVM/process binders so it can close the GC listener on shutdown.
            meterBinders = emptyList()
        }
    }
    // Security headers on every response. CSP is locked to 'self' (API returns JSON, admin
    // console is same-origin with no external resources); inline style/script are allowed
    // because the console uses them and is embedded in the same page.
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header(
            "Content-Security-Policy",
            "default-src 'self'; font-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'",
        )
    }
    // Rate-limit by client IP: throttles brute force/flooding on register, SRP, and pairing claim.
    // Behind a reverse proxy, the key comes from X-Forwarded-For but only when the direct peer is a
    // configured trusted proxy — otherwise a client could spoof the header to dodge limits (see
    // [rateLimitClientKey]). We do NOT install XForwardedHeaders: that would rewrite origin.remoteHost
    // globally and lose the direct peer we need to gate on.
    val trustedProxies = services.config.trustedProxies.toSet()
    install(RateLimit) {
        // All buckets are the same shape: N tokens per 60 seconds, keyed by client IP.
        fun perIp(name: RateLimitName, limit: Int) = register(name) {
            rateLimiter(limit = limit, refillPeriod = 60.seconds)
            requestKey { call ->
                rateLimitClientKey(
                    directPeer = call.request.origin.remoteHost,
                    forwardedFor = call.request.header(HttpHeaders.XForwardedFor),
                    trustedProxies = trustedProxies,
                )
            }
        }
        perIp(RateLimits.REGISTER, limit = 5)
        perIp(RateLimits.SRP_CHALLENGE, limit = 10)
        perIp(RateLimits.SRP_VERIFY, limit = 10)
        perIp(RateLimits.PAIRING_CLAIM, limit = 10)
        // Refresh needs no password; rate-limited as defense-in-depth even though the signature
        // check is cheap, since it's a public POST with no prior authentication.
        perIp(RateLimits.REFRESH, limit = 30)
        // Password rotation proves the current password via SRP; rate-limited like the SRP endpoints
        // as defense-in-depth (it's a public POST that swaps the verifier).
        perIp(RateLimits.CHANGE_PASSWORD, limit = 10)
        // The admin console uses a constant-time static token compare, which doesn't stop brute
        // forcing the token itself, hence a rate limit on /admin/*.
        perIp(RateLimits.ADMIN, limit = 30)
        // Same reasoning as the admin bucket: /metrics is guarded by a static token, and a static
        // token can be brute forced. A 15s scrape is 4 requests a minute, so this leaves 7x headroom.
        perIp(RateLimits.METRICS, limit = 30)
        // Session reports are a member-driven write into an audit log with a bounded retention
        // window, so they get a budget of their own — keyed by **account**, not by IP: an attacker
        // can't buy more of it by changing address, and members behind one NAT don't share one.
        // Generous for real use (a report per connect, per recording) and low enough that flooding
        // the feed takes sustained, plainly visible effort.
        register(RateLimits.TEAM_SESSION_EVENTS) {
            rateLimiter(limit = 60, refillPeriod = 60.seconds)
            requestKey { call ->
                call.principal<JWTPrincipal>()?.accountId ?: rateLimitClientKey(
                    directPeer = call.request.origin.remoteHost,
                    forwardedFor = call.request.header(HttpHeaders.XForwardedFor),
                    trustedProxies = trustedProxies,
                )
            }
        }
    }
    // Hard upper bound on request body size. Content-Length lets us reject oversized bodies with
    // 413 before reading. Content-Length alone isn't enough: a chunked-encoded body has none, so
    // the check below wouldn't trigger and call.receive would buffer an unbounded stream into
    // memory (OOM from a single unauthenticated request). Our client always sends Content-Length
    // for bodies, so POST/PUT without one is rejected as 411, closing the chunked bypass.
    val maxBody = services.config.maxRequestBodyBytes
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        val carriesBody = method == HttpMethod.Post || method == HttpMethod.Put || method == HttpMethod.Patch
        val len = call.request.contentLength()
        if (carriesBody && len == null) {
            services.metrics.requestRejected(RejectReason.LENGTH_REQUIRED)
            call.respond(HttpStatusCode.LengthRequired, ErrorResponse("Content-Length required"))
            return@intercept finish()
        }
        if (len != null && len > maxBody) {
            services.metrics.requestRejected(RejectReason.BODY_TOO_LARGE)
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("request body too large"))
            return@intercept finish()
        }
    }
    // CORS matters only to browser clients; native apps aren't subject to it and the admin
    // console is same-origin. Off by default (empty list); enabled by an explicit host list via
    // SKERRY_CORS_HOSTS.
    if (services.config.corsHosts.isNotEmpty()) {
        install(CORS) {
            services.config.corsHosts.forEach { allowHost(it.host, schemes = it.schemes) }
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowMethod(io.ktor.http.HttpMethod.Put)
            allowMethod(io.ktor.http.HttpMethod.Delete)
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<Throwable> { call, cause ->
            services.metrics.unhandledException()
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "skerry-sync"
            verifier(services.tokens.verifier())
            validate { credential ->
                val type = credential.payload.getClaim(TokenService.CLAIM_TYPE).asString()
                val account = credential.payload.subject
                val did = credential.payload.getClaim(TokenService.CLAIM_DEVICE).asString()
                // Valid only if this is an access token and the device (within the account) isn't revoked.
                when {
                    account == null || did == null -> {
                        services.metrics.jwtRejected(JwtRejection.MISSING_CLAIMS)
                        null
                    }
                    type != TokenService.TYPE_ACCESS -> {
                        services.metrics.jwtRejected(JwtRejection.WRONG_TYPE)
                        null
                    }
                    services.devices.isRevoked(account, did) -> {
                        services.metrics.jwtRejected(JwtRejection.DEVICE_REVOKED)
                        null
                    }
                    else -> JWTPrincipal(credential.payload)
                }
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        // Readiness is a separate endpoint on purpose: /healthz is wired to the container healthcheck
        // and to every client's availability ping, so making it depend on the database would turn a
        // slow transaction into a restart loop and a client-wide "unreachable" storm.
        get("/readyz") {
            val ready = services.dbProbe.ready
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadyResponse(if (ready) "ready" else "not_ready", if (ready) "up" else "down"),
            )
        }

        rateLimit(RateLimits.METRICS) {
            metricsRoutes(services)
        }

        // Root redirects to the admin console so opening the server in a browser isn't a 404.
        get("/") { call.respondRedirect("/console/") }

        // Static admin console (self-hosted): /console -> resources/admin/index.html.
        staticResources("/console", "admin")

        authRoutes(services)
        pairingClaimRoute(services)   // no JWT: the new device hasn't logged in yet
        rateLimit(RateLimits.ADMIN) {
            adminRoutes(services)     // own admin auth (static token) plus a brute-force rate limit
        }

        authenticate("auth-jwt") {
            vaultRoutes(services)
            deviceRoutes(services)
            pairingStartRoute(services)
            teamRoutes(services)
            teamScopeRoutes(services)
            shareRoutes(services)
            syncWebSocket(services)
        }
    }
}
