package app.skerry.shared.ai.local

/**
 * A live connection to an inference host — the process that owns the native llama.cpp library.
 * Line-oriented on purpose: the transport differs per platform (a child JVM over a Unix socket on
 * desktop, a `:llm` service over a socket pair on Android), the framing does not.
 */
interface LlmHostLink {

    /** Sends one [LlmHostProtocol] frame. Throws when the host is gone. */
    suspend fun send(line: String)

    /** Next frame from the host, or `null` once the host is gone (exit or native crash). */
    suspend fun receive(): String?

    /** Stops the host and releases the transport. Idempotent. */
    suspend fun close()
}

/** Starts an inference host and connects to it. */
fun interface LlmHostLauncher {
    suspend fun launch(): LlmHostLink
}
