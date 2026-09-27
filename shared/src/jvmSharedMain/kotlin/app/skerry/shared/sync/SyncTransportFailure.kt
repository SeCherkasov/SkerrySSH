package app.skerry.shared.sync

import app.skerry.shared.io.ResponseHeadTooLargeException
import app.skerry.shared.io.WebSocketUpgradeRefusedException
import app.skerry.shared.io.responseTooLarge

/**
 * A failed exchange with the sync server that produced no status answer, as a [SyncException]. An
 * answer past the client's size cap is the server's fault, not the network's: PROTOCOL, so it is
 * not retried as a dropped connection would be. A refused WebSocket upgrade did get a status, and
 * is classified by it as any other answer is.
 */
internal fun Exception.toSyncTransportFailure(what: String = "network error"): SyncException {
    val refused = generateSequence<Throwable>(this) { it.cause }.filterIsInstance<WebSocketUpgradeRefusedException>().firstOrNull()
    if (refused != null) return SyncException(syncKindOf(refused.status), "server responded ${refused.status}", this, refused.status)
    responseTooLarge()?.let { return SyncException(SyncException.Kind.PROTOCOL, "server response exceeds ${it.limit} bytes", this) }
    if (this is ResponseHeadTooLargeException) return SyncException(SyncException.Kind.PROTOCOL, message.orEmpty(), this)
    return SyncException(SyncException.Kind.NETWORK, "$what: $message", this)
}

/** What a non-2xx status from the sync server means to the client. */
internal fun syncKindOf(status: Int): SyncException.Kind = when (status) {
    401 -> SyncException.Kind.UNAUTHORIZED
    403 -> SyncException.Kind.FORBIDDEN
    404 -> SyncException.Kind.NOT_FOUND
    409 -> SyncException.Kind.CONFLICT
    410 -> SyncException.Kind.GONE
    429 -> SyncException.Kind.TOO_MANY_REQUESTS
    // Whole range, not just 500/502/503: a proxy can answer with codes the server never
    // emits, and they all mean the same to the user — not your fault, retry later.
    in 500..599 -> SyncException.Kind.SERVER_ERROR
    else -> SyncException.Kind.PROTOCOL
}
