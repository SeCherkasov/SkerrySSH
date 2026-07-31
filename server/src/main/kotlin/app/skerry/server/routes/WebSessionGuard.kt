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
 * The rule is therefore: read-only, minus the two reads that carry ciphertext, plus the single
 * write the account zone offers — revoking a device. A web session is recognised by its device id,
 * which the server assigns itself at sign-in (see [WebSession]) and which travels inside the signed
 * token, so it cannot be claimed by a caller.
 */
private val CIPHERTEXT_PATHS = setOf("/vault/keys", "/vault/records")

internal fun webSessionMayCall(method: HttpMethod, rawPath: String): Boolean {
    val path = routedPath(rawPath) ?: return false
    return when {
        // Team records are the same blobs under a different key, and there is no envelope projection
        // for them — a browser has no business holding either.
        path in CIPHERTEXT_PATHS || (path.startsWith("/teams/") && path.endsWith("/records")) -> false
        method == HttpMethod.Get -> true
        method == HttpMethod.Delete && path.startsWith("/devices/") -> true
        else -> false
    }
}

/**
 * The path the router will match, rebuilt the way [io.ktor.server.routing.RoutingResolveContext]
 * builds it: empty segments dropped, each remaining one percent-decoded.
 *
 * `call.request.path()` is the raw request target, and the router does not match on it — so
 * `/vault/%72ecords` and `/vault//records` both reach the handler that hands over every ciphertext
 * blob while a rule comparing the raw string sees a path it doesn't recognise and falls through to
 * "a GET is fine". Two parsers deciding the same question have to agree; this one follows the
 * router's.
 *
 * `null` for a target that can't be decoded, or whose decoded segment carries a separator of its own
 * (`%2F`) — neither resolves to a route this guard has an opinion about, and refusing is the answer
 * that cannot be wrong.
 */
private fun routedPath(rawPath: String): String? {
    val segments = rawPath.split('/').filter { it.isNotEmpty() }.map { segment ->
        val decoded = try {
            segment.decodeURLPart()
        } catch (_: URLDecodeException) {
            return null
        }
        if ('/' in decoded) return null
        decoded
    }
    return segments.joinToString(separator = "/", prefix = "/")
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
