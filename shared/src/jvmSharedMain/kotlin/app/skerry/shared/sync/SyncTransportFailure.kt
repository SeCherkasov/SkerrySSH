package app.skerry.shared.sync

import app.skerry.shared.io.responseTooLarge

/**
 * A failed exchange with the sync server that produced no status answer, as a [SyncException]. An
 * answer past the client's size cap is the server's fault, not the network's: PROTOCOL, so it is
 * not retried as a dropped connection would be.
 */
internal fun Exception.toSyncTransportFailure(what: String = "network error"): SyncException =
    responseTooLarge()?.let { SyncException(SyncException.Kind.PROTOCOL, "server response exceeds ${it.limit} bytes", this) }
        ?: SyncException(SyncException.Kind.NETWORK, "$what: $message", this)
