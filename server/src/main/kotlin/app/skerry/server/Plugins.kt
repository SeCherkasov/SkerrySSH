package app.skerry.server

import app.skerry.server.auth.TokenService
import app.skerry.server.model.ErrorResponse
import app.skerry.server.routes.adminRoutes
import app.skerry.server.routes.authRoutes
import app.skerry.server.routes.deviceRoutes
import app.skerry.server.routes.pairingClaimRoute
import app.skerry.server.routes.pairingStartRoute
import app.skerry.server.routes.syncWebSocket
import app.skerry.server.routes.teamRoutes
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
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Rate-limit bucket names (keyed by remote IP). Declared here, used by the routes. */
object RateLimits {
    val REGISTER = RateLimitName("auth-register")
    val SRP_CHALLENGE = RateLimitName("srp-challenge")
    val SRP_VERIFY = RateLimitName("srp-verify")
    val PAIRING_CLAIM = RateLimitName("pairing-claim")
    val REFRESH = RateLimitName("auth-refresh")
    val ADMIN = RateLimitName("admin")
}

/** Server version for /healthz and the admin console. */
const val SERVER_VERSION = "0.1.0"

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
    install(CallLogging) { level = Level.INFO }
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
        // The admin console uses a constant-time static token compare, which doesn't stop brute
        // forcing the token itself, hence a rate limit on /admin/*.
        perIp(RateLimits.ADMIN, limit = 30)
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
            call.respond(HttpStatusCode.LengthRequired, ErrorResponse("Content-Length required"))
            return@intercept finish()
        }
        if (len != null && len > maxBody) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("request body too large"))
            return@intercept finish()
        }
    }
    // CORS matters only to browser clients; native apps aren't subject to it and the admin
    // console is same-origin. Off by default (empty list); enabled by an explicit host list via
    // SKERRY_CORS_HOSTS.
    if (services.config.corsHosts.isNotEmpty()) {
        install(CORS) {
            services.config.corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
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
                if (type == TokenService.TYPE_ACCESS && account != null && did != null &&
                    !services.devices.isRevoked(account, did)
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

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
            syncWebSocket(services)
        }
    }
}
