package app.skerry.shared.ai.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * [LlmHostLink] over a pair of byte streams — a Unix socket to a child JVM on desktop, a socket
 * pair to the `:llm` service on Android. [stop] tears the host itself down (destroy the process,
 * unbind the service) and runs once, after the streams are closed.
 *
 * A dead host shows up as a closed stream: [receive] answers `null` instead of throwing, which is
 * exactly what [IsolatedLlmRuntime] treats as a crash.
 */
class StreamLlmHostLink(
    input: InputStream,
    output: OutputStream,
    private val stop: () -> Unit,
) : LlmHostLink {

    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val writer = output.writer(Charsets.UTF_8)
    private val sending = Mutex()

    @Volatile
    private var closed = false

    // runInterruptible, not a plain withContext: these are blocking calls with no suspension point,
    // so without it a timeout around them could not stop waiting for a host that went quiet.
    override suspend fun send(line: String) {
        sending.withLock {
            runInterruptible(Dispatchers.IO) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    override suspend fun receive(): String? = runInterruptible(Dispatchers.IO) {
        runCatching { reader.readLine() }.getOrNull()
    }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            if (closed) return@withContext
            closed = true
            // Closing the transport is also how a read that is already blocked gets released:
            // both a socket channel (desktop) and an Android fd report the asynchronous close.
            runCatching { writer.close() }
            runCatching { reader.close() }
            runCatching { stop() }
        }
    }
}
