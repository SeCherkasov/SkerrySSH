package app.skerry.shared.share

import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.sync.wire.SharesResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import java.util.Base64

/**
 * JVM (desktop + Android) implementation of [SessionShareClient] over the same Ktor client the sync
 * client uses. Frames arrive sealed and are passed through untouched — this layer only knows the
 * difference between the session's data (binary frames) and the relay's own control messages
 * (text frames: `viewers:N:...` and the `from:` line that names the socket the next frame arrived
 * on). An unknown control line is ignored, which is what lets a server add one without breaking
 * clients older than it.
 *
 * [serverUrl] — base HTTP(S) URL with no trailing slash, as for
 * [app.skerry.shared.sync.KtorSyncClient].
 */
class KtorSessionShareClient(
    private val serverUrl: String,
    private val http: HttpClient,
) : SessionShareClient {

    override suspend fun listShares(session: SyncSession, teamId: String): List<SharedSessionInfo> {
        val resp = request { http.get("$serverUrl/teams/${teamId.encodeURLPathPart()}/shares") { bearerAuth(session.accessToken) } }
        if (!resp.status.isSuccess()) throw resp.toException()
        return resp.body<SharesResponse>().shares
            // The directory comes from the server: a malformed id would go straight back into a URL
            // path, and an unopenable meta blob is dropped later by the caller's codec anyway.
            .filter { isValidShareId(it.shareId) }
            .mapNotNull { dto ->
                val meta = runCatching { Base64.getDecoder().decode(dto.meta) }.getOrNull() ?: return@mapNotNull null
                SharedSessionInfo(dto.shareId, dto.hostAccountId, meta, dto.startedAt, dto.viewers)
            }
    }

    override suspend fun hostShare(
        session: SyncSession,
        teamId: String,
        shareId: String,
        meta: ByteArray,
        block: suspend (ShareChannel) -> Unit,
    ) {
        require(isValidShareId(shareId)) { "invalid shareId" }
        val encodedMeta = Base64.getEncoder().encodeToString(meta).encodeURLParameter()
        connect("${sharePath(teamId, shareId)}/host?meta=$encodedMeta", session, block)
    }

    override suspend fun joinShare(
        session: SyncSession,
        teamId: String,
        shareId: String,
        block: suspend (ShareChannel) -> Unit,
    ) {
        require(isValidShareId(shareId)) { "invalid shareId" }
        connect("${sharePath(teamId, shareId)}/join", session, block)
    }

    private suspend fun connect(path: String, session: SyncSession, block: suspend (ShareChannel) -> Unit) {
        val wsUrl = serverUrl.replaceFirst("http", "ws") + path
        try {
            http.webSocket(urlString = wsUrl, request = { bearerAuth(session.accessToken) }) {
                block(WebSocketShareChannel(this))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncException.Kind.NETWORK, "share relay error: ${e.message}", e)
        }
    }

    private fun sharePath(teamId: String, shareId: String) =
        "/teams/${teamId.encodeURLPathPart()}/shares/${shareId.encodeURLPathPart()}"

    private fun HttpStatusCode.isSuccess() = value in 200..299

    private suspend fun request(call: suspend () -> HttpResponse): HttpResponse = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncException(SyncException.Kind.NETWORK, "network error: ${e.message}", e)
    }

    private fun HttpResponse.toException(): SyncException {
        val kind = when (status) {
            HttpStatusCode.Unauthorized -> SyncException.Kind.UNAUTHORIZED
            HttpStatusCode.NotFound -> SyncException.Kind.NOT_FOUND
            HttpStatusCode.Forbidden -> SyncException.Kind.FORBIDDEN
            else -> if (status.value in 500..599) SyncException.Kind.SERVER_ERROR else SyncException.Kind.PROTOCOL
        }
        return SyncException(kind, "server responded ${status.value}")
    }
}

/** [ShareChannel] over a live Ktor WebSocket session. */
private class WebSocketShareChannel(
    private val socket: DefaultClientWebSocketSession,
) : ShareChannel {

    override suspend fun send(frame: ByteArray) = socket.send(Frame.Binary(true, frame))

    override suspend fun receive(): ShareEvent? {
        // Who the relay says sent the next frame, from the `from:` line that precedes it. The server
        // writes the two as one unit, so this only ever spans the pair; a server that sends no
        // `from:` line leaves it null and the host names nobody.
        var from: String? = null
        for (frame in socket.incoming) {
            when (frame) {
                is Frame.Binary -> return ShareEvent.Data(frame.readBytes(), from)
                // The relay's own channel. An unparseable or unknown control line is ignored rather
                // than treated as an error: a newer server may add ones this client doesn't know.
                is Frame.Text -> {
                    val text = frame.readText()
                    if (text.startsWith(FROM_PREFIX)) {
                        from = decodeAccount(text.removePrefix(FROM_PREFIX))
                        continue
                    }
                    parseControl(text)?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }

    override suspend fun close() {
        socket.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
    }

    private fun parseControl(text: String): ShareEvent? {
        if (!text.startsWith(VIEWERS_PREFIX)) return null
        val body = text.removePrefix(VIEWERS_PREFIX)
        val count = body.substringBefore(':').toIntOrNull() ?: return null
        // The account list is base64 per id (see the server's frame): an id that doesn't decode is
        // dropped rather than shown as mojibake next to someone's session.
        val accounts = body.substringAfter(':', "")
            .split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { decodeAccount(it) }
        return ShareEvent.Viewers(count.coerceAtLeast(0), accounts)
    }

    /** One base64 account id from a control line, or null if the relay made it up. */
    private fun decodeAccount(encoded: String): String? =
        runCatching { Base64.getDecoder().decode(encoded).decodeToString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_ACCOUNT_CHARS }

    private companion object {
        const val VIEWERS_PREFIX = "viewers:"
        const val FROM_PREFIX = "from:"

        /** Mirrors the server's account-id cap; a longer one is the relay making things up. */
        const val MAX_ACCOUNT_CHARS = 320
    }
}
