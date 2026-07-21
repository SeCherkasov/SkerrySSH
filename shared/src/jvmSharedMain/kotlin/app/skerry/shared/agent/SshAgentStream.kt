package app.skerry.shared.agent

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runInterruptible

/**
 * Threads the agent may take from the shared IO pool, across every carrier and every session.
 *
 * Each served connection parks one thread in a blocking read for as long as the peer keeps it open,
 * and the peers are untrusted: a server the user forwards to can open agent channels and then go
 * quiet. Without a ceiling those reads would eat `Dispatchers.IO`, which the whole app shares with
 * SFTP, tunnels and every other session — so a single hostile host could stall features that have
 * nothing to do with the agent. A deadline is not an option instead: a legitimate `ssh` holds its
 * agent connection open, idle, for the life of the remote shell.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val agentDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_AGENT_THREADS)

/** See [agentDispatcher]. Generous next to what one user's own tooling opens, tiny next to the pool. */
private const val MAX_AGENT_THREADS = 16

/**
 * Serve the agent protocol over one byte stream pair until the peer goes away. Shared by both
 * carriers — a forwarded `auth-agent@openssh.com` channel and the local `SSH_AUTH_SOCK` socket —
 * so framing and refusal behaviour cannot drift apart between them.
 *
 * The peer is untrusted in both cases: a length that is absurd or a message the codec cannot parse
 * ends the connection rather than resynchronising, because there is no way to find the next message
 * boundary in a stream once the length prefix is not to be trusted.
 *
 * Blocking reads run under [runInterruptible], so cancelling the serving coroutine (session
 * teardown, vault lock, app exit) actually interrupts a read that is waiting for the next request
 * instead of leaking a thread until the peer closes.
 */
internal suspend fun serveSshAgent(
    input: InputStream,
    output: OutputStream,
    service: SshAgentService,
    origin: SshAgentOrigin,
) {
    try {
        while (true) {
            val header = runInterruptible { input.readFullyOrNull(HEADER_BYTES) } ?: return
            val length = (header[0].toInt() and 0xFF shl 24) or
                (header[1].toInt() and 0xFF shl 16) or
                (header[2].toInt() and 0xFF shl 8) or
                (header[3].toInt() and 0xFF)
            if (length <= 0 || length > SshAgentCodec.MAX_MESSAGE_BYTES) return
            val request = runInterruptible { input.readFullyOrNull(length) } ?: return
            val response = service.handle(request, origin)
            runInterruptible {
                output.write(SshAgentCodec.frame(response))
                output.flush()
            }
        }
    } catch (e: IOException) {
        // Peer closed mid-message (session torn down, `ssh` exited): nothing to report.
    }
}

private const val HEADER_BYTES = 4

/** Read exactly [count] bytes, or `null` at end of stream. */
private fun InputStream.readFullyOrNull(count: Int): ByteArray? {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) return null
        read += n
    }
    return buffer
}
