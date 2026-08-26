package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.deviceId
import app.skerry.server.db.TeamMemberStatus
import app.skerry.server.jwtPrincipal
import app.skerry.server.share.GuestFrame
import app.skerry.server.share.GuestShareSession
import app.skerry.server.share.HostShareSession
import app.skerry.server.share.ShareJoin
import app.skerry.server.share.ShareOpen
import app.skerry.sync.wire.ShareDto
import app.skerry.sync.wire.SharesResponse
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/** Longest sealed session label a host may register (base64 chars) — a name, not a payload. */
private const val MAX_SHARE_META_CHARS = 1024

/** Share ids are client-generated; the same restricted alphabet as scope ids (see [validScopeId]). */
private const val MAX_SHARE_ID = 64

/**
 * Live session sharing: a member streams one of their terminals to their team, and other members
 * watch it — typing back when the host allows it (the host, not the relay, decides that).
 *
 * Zero-knowledge: every frame is sealed under the team key before it reaches the server, so the
 * relay routes opaque blobs and keeps nothing. Binary frames are the session's data and are
 * relayed; text frames are the server's own control channel to the host (`viewers:N`, and `from:`
 * naming the socket a keystroke frame arrived on) and are never forwarded.
 *
 * Authorization is checked at connect **and** re-checked while the socket lives (see
 * [watchAccess]): a JWT is verified only at handshake, so a member removed from the team, or a
 * device revoked mid-session, would otherwise keep watching a live shell.
 */
fun Route.shareRoutes(services: Services) {
    get("/teams/{id}/shares") {
        val principal = call.jwtPrincipal()
        val teamId = call.requiredPathId("id") ?: return@get
        call.requireActiveMember(services, teamId, principal.accountId) ?: return@get
        call.respond(
            SharesResponse(
                services.shares.list(teamId).map {
                    ShareDto(it.shareId, it.hostAccountId, it.meta, it.startedAt, it.viewers)
                },
            ),
        )
    }

    webSocket("/teams/{id}/shares/{shareId}/host") {
        val request = accept(services) ?: return@webSocket
        val meta = call.request.queryParameters["meta"].orEmpty()
        if (meta.length > MAX_SHARE_META_CHARS) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "meta too large"))
            return@webSocket
        }
        when (val opened = services.shares.open(request.teamId, request.shareId, request.accountId, meta)) {
            ShareOpen.Taken -> close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "share id in use"))
            ShareOpen.TooMany -> close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many shares in this team"))
            is ShareOpen.Started -> relayHost(services, request, opened.session)
        }
    }

    webSocket("/teams/{id}/shares/{shareId}/join") {
        val request = accept(services) ?: return@webSocket
        when (val joined = services.shares.join(request.teamId, request.shareId, request.accountId)) {
            ShareJoin.NoShare -> close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such share"))
            ShareJoin.Full -> close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "share is full"))
            is ShareJoin.Joined -> relayGuest(services, request, joined.session)
        }
    }
}

/** An authorized share socket: who is on it and which share they asked for. */
private class ShareRequest(
    val teamId: String,
    val shareId: String,
    val accountId: String,
    val deviceId: String,
)

/**
 * Validates and authorizes a share socket, closing it and returning null on any refusal. Membership
 * is checked here rather than by [requireActiveMember] because the socket is already upgraded: the
 * answer has to be a close reason, not an HTTP status.
 */
private suspend fun DefaultWebSocketServerSession.accept(services: Services): ShareRequest? {
    val principal = call.principal<JWTPrincipal>()
    if (principal == null) {
        // Defense-in-depth, as in the /sync socket: the route sits under authenticate("auth-jwt").
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "authentication required"))
        return null
    }
    val teamId = call.parameters["id"].orEmpty()
    val shareId = call.parameters["shareId"].orEmpty()
    if (teamId.isBlank() || anyTooLong(teamId) || !isSafeShareId(shareId)) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "bad share address"))
        return null
    }
    if (!services.hasShareAccess(teamId, principal.accountId, principal.deviceId)) {
        // Same answer for "not a member", "invite not accepted" and "device revoked": a socket is
        // not the place to explain which, and the HTTP routes already draw that line.
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not an active member"))
        return null
    }
    return ShareRequest(teamId, shareId, principal.accountId, principal.deviceId)
}

/** The host's socket: its binary frames fan out to the viewers; their input and count come back. */
private suspend fun DefaultWebSocketServerSession.relayHost(
    services: Services,
    request: ShareRequest,
    session: HostShareSession,
) {
    services.notifier.publishShares(request.teamId)
    val writer = HostSocketWriter { send(it) }
    val input = launch {
        while (true) writer.input(session.receiveInput() ?: break)
    }
    val viewers = launch {
        while (true) writer.viewers(session.receiveViewers() ?: break)
    }
    val access = watchAccess(services, request)
    try {
        for (frame in incoming) {
            if (frame is Frame.Binary) session.broadcast(frame.readBytes())
        }
    } finally {
        input.cancel()
        viewers.cancel()
        access.cancel()
        session.end()
        // The directory changed twice over this socket's life; the closing signal is what takes the
        // ended session off every member's list.
        services.notifier.publishShares(request.teamId)
    }
}

/**
 * Everything the host's socket is told, written by one writer.
 *
 * Two sources feed it — a viewer's keystrokes and the list of who is watching — and a `from:` line
 * names only the frame that follows it immediately, so the pair goes out as one unit. A viewer list
 * landing in the gap would leave the host holding keystrokes it cannot attribute, which on the
 * screen is a control prompt that never appears. Kept out of the route so that property can be
 * tested without a socket.
 */
internal class HostSocketWriter(private val send: suspend (Frame) -> Unit) {

    private val writing = Mutex()

    /**
     * One viewer's sealed frame, preceded by the account the relay authenticated its socket as —
     * not anything the frame claims about itself (#312).
     */
    suspend fun input(frame: GuestFrame) = writing.withLock {
        send(Frame.Text("from:${encode(frame.from)}"))
        send(Frame.Binary(true, frame.bytes))
    }

    /** `viewers:{count}:{account},{account}` — who is on the session right now. */
    suspend fun viewers(watching: List<String>) = writing.withLock {
        send(Frame.Text("viewers:${watching.size}:${watching.joinToString(",") { encode(it) }}"))
    }

    /** Base64 so an account id can never introduce a separator of its own into a control line. */
    private fun encode(accountId: String): String =
        Base64.getEncoder().encodeToString(accountId.toByteArray())
}

/** A viewer's socket: the host's frames go out, the viewer's keystrokes go back to the host. */
private suspend fun DefaultWebSocketServerSession.relayGuest(
    services: Services,
    request: ShareRequest,
    session: GuestShareSession,
) {
    val stream = launch {
        while (true) send(Frame.Binary(true, session.receive() ?: break))
        // The host ended the session (or this viewer fell too far behind to follow it).
        close(CloseReason(CloseReason.Codes.NORMAL, "share ended"))
    }
    val access = watchAccess(services, request)
    try {
        for (frame in incoming) {
            if (frame is Frame.Binary) session.sendToHost(frame.readBytes())
        }
    } finally {
        stream.cancel()
        access.cancel()
        session.leave()
    }
}

/**
 * Re-checks periodically that this socket's owner may still be here — active membership and a
 * live device — and closes it when they may not. Without this, removing a member or revoking a
 * device would not take effect until their access token expires and they reconnect, which for a
 * live terminal stream is exactly the window that matters.
 */
private fun DefaultWebSocketServerSession.watchAccess(services: Services, request: ShareRequest): Job = launch {
    while (true) {
        delay(services.shareAccessRecheckMillis)
        if (!services.hasShareAccess(request.teamId, request.accountId, request.deviceId)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "access revoked"))
            return@launch
        }
    }
}

/** Whether [accountId] may be on a share socket of [teamId] from device [deviceId] right now. */
private suspend fun Services.hasShareAccess(teamId: String, accountId: String, deviceId: String): Boolean {
    if (devices.isRevoked(accountId, deviceId)) return false
    val membership = teams.membership(teamId, accountId) ?: return false
    return membership.status == TeamMemberStatus.ACTIVE
}

/** Share ids come from the client; the alphabet keeps them out of paths and logs as anything else. */
private fun isSafeShareId(value: String): Boolean =
    value.isNotEmpty() && value.length <= MAX_SHARE_ID &&
        value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
