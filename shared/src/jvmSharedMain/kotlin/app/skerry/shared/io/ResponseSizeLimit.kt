package app.skerry.shared.io

import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import java.io.IOException

private const val COPY_CHUNK_BYTES = 16 * 1024

/** A response body ran past the client's [ResponseSizeLimit]; the rest of it was never read. */
class ResponseTooLargeException(val limit: Long) : IOException("response exceeds $limit bytes")

/**
 * The [ResponseTooLargeException] behind this failure, if any. Ktor hands a body failure to the
 * reader through a cancelled channel, which may wrap the cause, so the whole chain is searched.
 */
fun Throwable.responseTooLarge(): ResponseTooLargeException? =
    generateSequence(this) { it.cause }.filterIsInstance<ResponseTooLargeException>().firstOrNull()

class ResponseSizeLimitConfig {
    /** Largest response body, in bytes, that the client reads before failing the call. Required. */
    var maxBytes: Long = 0
}

/**
 * Fails a response whose body runs past [ResponseSizeLimitConfig.maxBytes], stopping the read there.
 * The cap covers the body only; the header section is the engine's (see [untrustedHttpClient]).
 *
 * Ktor 3 holds every non-streamed response whole in memory before client code sees it, so a cap
 * applied to the body afterwards bounds what is kept, not what was read: a broken or hostile server
 * can still run the app out of memory. The cap therefore sits in front of that save, in its own
 * phase ahead of [HttpReceivePipeline.Before], and holds for streamed responses too. A declared
 * `Content-Length` past the cap fails before any of the body is read; an undeclared or understated
 * one fails at the first byte past it. A `101 Switching Protocols` answer carries no body and is left
 * alone, or the WebSocket session built on it would be cut off.
 */
@OptIn(InternalAPI::class)
val ResponseSizeLimit = createClientPlugin("ResponseSizeLimit", ::ResponseSizeLimitConfig) {
    val maxBytes = pluginConfig.maxBytes
    require(maxBytes > 0) { "ResponseSizeLimit needs a positive maxBytes" }
    val phase = PipelinePhase("ResponseSizeLimit")
    client.receivePipeline.insertPhaseBefore(HttpReceivePipeline.Before, phase)
    client.receivePipeline.intercept(phase) { response ->
        if (response.status == HttpStatusCode.SwitchingProtocols) return@intercept
        val origin = response.rawContent
        val declared = response.contentLength()
        if (declared != null && declared > maxBytes) {
            val failure = ResponseTooLargeException(maxBytes)
            origin.cancel(failure)
            throw failure
        }
        val capped = response.copyCapped(origin, maxBytes)
        proceedWith(response.call.replaceResponse { capped }.response)
    }
}

/**
 * Copies [origin] into a new channel until it ends or passes [maxBytes]. Never asks [origin] for
 * more than one byte past the cap, so the overflow is detected without reading it.
 */
private fun CoroutineScope.copyCapped(origin: ByteReadChannel, maxBytes: Long): ByteReadChannel = writer {
    val chunk = ByteArray(COPY_CHUNK_BYTES)
    var total = 0L
    var drained = false
    try {
        while (true) {
            // Room is at most one byte past the cap, computed without `maxBytes + 1` overflowing.
            val room = maxBytes - total
            val read = origin.readAvailable(chunk, 0, if (room >= chunk.size) chunk.size else room.toInt() + 1)
            if (read == -1) {
                // A cancelled origin reads as -1 too: a connection dropped mid-body must not pass
                // for a body that ended.
                origin.closedCause?.let { throw it }
                break
            }
            total += read
            if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
            channel.writeFully(chunk, 0, read)
            channel.flush()
        }
        drained = true
    } finally {
        // Past the cap, or the reader walked away: either way the connection must not keep feeding
        // a body nobody reads.
        if (!drained) origin.cancel()
    }
}.channel
