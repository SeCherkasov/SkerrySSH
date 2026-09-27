package app.skerry.shared.io

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Protocol

/** Whole-call bound for a request/response exchange: what CIO applied by default, kept on the new engine. */
const val UNTRUSTED_REQUEST_TIMEOUT_MS = 15_000L

/** CIO's default connect timeout, kept so a dead host fails as fast as before. */
internal const val CONNECT_TIMEOUT_MS = 5_000L

/**
 * An HTTP client for a server the app does not control: the sync server, a user-typed AI endpoint,
 * a model or release host. Both halves of an answer are bounded before the client code sees them:
 * the body by [ResponseSizeLimit], which each caller installs with its own cap, and the header
 * section by the engine.
 *
 * OkHttp rather than CIO for that second half: CIO bounds each header line and not how many there
 * are, so a hostile server could stream headers until the app ran out of memory. OkHttp reads at
 * most 256 KiB of them. It is held to HTTP/1.1, the protocol that limit applies to — an HTTP/2
 * header block is bounded differently, and none of these servers needs HTTP/2. OkHttp's silent
 * retry of a failed request is off too: CIO never retried, and a retried SRP proof or pairing claim
 * would be spent twice.
 *
 * [requestTimeoutMillis] covers the whole call, body included; `null` leaves the call unbounded
 * (a download) while connect and per-read timeouts still catch a dead peer.
 */
fun untrustedHttpClient(
    requestTimeoutMillis: Long? = UNTRUSTED_REQUEST_TIMEOUT_MS,
    block: HttpClientConfig<OkHttpConfig>.() -> Unit = {},
): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            protocols(listOf(Protocol.HTTP_1_1))
            retryOnConnectionFailure(false)
        }
    }
    install(HttpTimeout) {
        this.requestTimeoutMillis = requestTimeoutMillis
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        // OkHttp's own read timeout is 10 s; CIO let a whole call take 15 s, so a slow first byte
        // (an AI endpoint thinking) must not fail sooner than it used to.
        socketTimeoutMillis = UNTRUSTED_REQUEST_TIMEOUT_MS
    }
    block()
}
