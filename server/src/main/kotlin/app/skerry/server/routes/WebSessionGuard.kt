package app.skerry.server.routes

import app.skerry.server.db.WebSession
import app.skerry.server.deviceId
import app.skerry.server.model.ErrorResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** Placeholder for one path parameter — a team id, a device id. Never a literal segment. */
private const val ANY_SEGMENT = "*"

private val ALLOWED_GETS = listOf(
    listOf("account", "summary"),
    listOf("account", "activity"),
    listOf("vault", "envelopes"),
    listOf("devices"),
    listOf("teams"),
    listOf("teams", ANY_SEGMENT, "members"),
    listOf("teams", ANY_SEGMENT, "scopes"),
    listOf("teams", ANY_SEGMENT, "activity"),
    listOf("teams", ANY_SEGMENT, "shares"),
)

/** Revoking a device, the one thing the account zone can change. */
private val ALLOWED_DELETES = listOf(listOf("devices", ANY_SEGMENT))

/**
 * What a browser signed in with the **web password** may do with the token it got.
 *
 * That token is otherwise an ordinary account token, and `authenticate("auth-jwt")` asks only
 * whether it is an access token for a live device — so without this guard the web password, the
 * lesser credential of the two, would open everything the master password protects on the server
 * side: `GET /vault/keys` and `GET /vault/records` hand over the wrapped dataKey and every
 * ciphertext blob, which is precisely the material an offline attack on the master password needs;
 * `POST /auth/web-password` would let whoever holds the browser session rotate the credential and
 * lock the owner out of their own zone; `/pairing/start` would enrol a device that clearing the web
 * password does not revoke, since the clear only revokes `platform = "web"` rows.
 *
 * The rule is therefore an **allow-list**: exactly the reads the account zone makes, plus the single
 * write it offers — revoking a device. Everything else is refused, including a route that does not
 * exist yet. A deny-list phrased as "any GET is a read" was the earlier shape and it was wrong twice
 * over: a WebSocket handshake is a GET, so it let a browser session onto the share relay
 * ([shareRoutes]) — `/host` opens a live session under the account's name and burns the per-team
 * share cap, `/join` takes a viewer slot and relays frames back to a real host — and every future
 * route under `authenticate("auth-jwt")` would have opened itself the same way, silently.
 *
 * A web session is recognised by its device id, which the server assigns itself at sign-in (see
 * [WebSession]) and which travels inside the signed token, so it cannot be claimed by a caller.
 */
internal fun webSessionMayCall(method: HttpMethod, rawPath: String): Boolean {
    val segments = routedSegments(rawPath) ?: return false
    val allowed = when (method) {
        HttpMethod.Get -> ALLOWED_GETS
        HttpMethod.Delete -> ALLOWED_DELETES
        else -> return false
    }
    return allowed.any { it.matches(segments) }
}

/** A path pattern matches when it has the same length and every segment agrees, `*` with any one. */
private fun List<String>.matches(segments: List<String>): Boolean =
    size == segments.size && indices.all { this[it] == ANY_SEGMENT || this[it] == segments[it] }

/**
 * The segments the router will match on, rebuilt the way [io.ktor.server.routing.RoutingResolveContext]
 * builds them: empty ones dropped, each remaining one percent-decoded.
 *
 * `call.request.path()` is the raw request target, and the router does not match on it — so
 * `/vault/%72ecords` and `/vault//records` both reach the handler that hands over every ciphertext
 * blob while a rule comparing the raw string sees a path it doesn't recognise. Two parsers deciding
 * the same question have to agree; this one follows the router's.
 *
 * `null` for a target that can't be decoded, or whose decoded segment carries a separator of its own
 * (`%2F`) — neither resolves to a route this guard has an opinion about, and refusing is the answer
 * that cannot be wrong.
 */
private fun routedSegments(rawPath: String): List<String>? =
    rawPath.split('/').filter { it.isNotEmpty() }.map { segment ->
        val decoded = try {
            segment.decodeURLPart()
        } catch (_: URLDecodeException) {
            return null
        }
        if ('/' in decoded) return null
        decoded
    }

/**
 * Runs in the `Call` phase, for two reasons: it needs `finish()` to keep the route handler from
 * running after the 403 (`onCall` cannot), and it needs the principal, which the authentication
 * phase only sets after `Plugins` — a guard installed there would see `null` on every call and wave
 * everything through. Interceptors of an ancestor node run before the handler of the matched route,
 * so one plugin on the `authenticate` node covers every route under it.
 */
private object WebSessionHook : Hook<suspend (ApplicationCall) -> Boolean> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall) -> Boolean) {
        pipeline.intercept(ApplicationCallPipeline.Call) {
            if (!handler(call)) finish()
        }
    }
}

/** Installed on the `authenticate("auth-jwt")` node, so it sees every route a token can reach. */
val WebSessionScope = createRouteScopedPlugin("WebSessionScope") {
    on(WebSessionHook) { call ->
        val principal = call.principal<JWTPrincipal>()
        val allowed = principal == null ||
            principal.deviceId != WebSession.DEVICE_ID ||
            webSessionMayCall(call.request.httpMethod, call.request.path())
        if (!allowed) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("not available to a web session"))
        }
        allowed
    }
}
