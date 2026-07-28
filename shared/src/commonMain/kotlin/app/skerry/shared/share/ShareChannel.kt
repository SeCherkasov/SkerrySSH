package app.skerry.shared.share

/** What a share socket delivers: peer frames (sealed, opaque to the relay) and relay control news. */
sealed interface ShareEvent {
    /** A sealed frame from the peer — output for a viewer, keystrokes for a host. */
    class Data(val frame: ByteArray) : ShareEvent

    /**
     * Who is watching right now (host sockets only). [accounts] is what the host's UI shows beside
     * the session; [count] is the relay's own number, which can be larger if a newer server ever
     * reports viewers it does not name.
     */
    class Viewers(val count: Int, val accounts: List<String> = emptyList()) : ShareEvent
}

/**
 * One end of a share relay socket, as the sharing logic sees it. Kept as an interface in
 * `commonMain` so [SessionShareHost] and [SharedSessionViewer] are testable without a server: the
 * Ktor implementation lives beside the sync client.
 */
interface ShareChannel {
    /** Sends a sealed frame. */
    suspend fun send(frame: ByteArray)

    /** Next event, or `null` once the socket is closed. */
    suspend fun receive(): ShareEvent?

    /** Closes the socket; idempotent. */
    suspend fun close()
}
