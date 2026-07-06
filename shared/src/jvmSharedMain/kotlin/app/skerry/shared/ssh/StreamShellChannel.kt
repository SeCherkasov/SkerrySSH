package app.skerry.shared.ssh

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Base for interactive channels with blocking reads (sshj / Telnet / Serial): shared read-loop
 * scaffolding + single-collector guard for [output], idempotent [close], traffic counters, and a
 * normal-EOF flag. Subclasses implement [readBlocking]/[closeSource] and optionally override
 * [transform] (protocol decoding) and write/resize.
 *
 * Loop semantics: IOException from [readBlocking] means normal termination (transport drop or our
 * own [close]); CancellationException from runInterruptible propagates out — that's cancellation
 * of the collector, not end of data.
 *
 * @param unblockReadOnCancel true for sources whose blocking read does NOT respond to
 *   Thread.interrupt (raw socket, native serial): when the collector's Job completes, the source
 *   is closed via [closeSource], dropping the hung read as an IOException. sshj's read is
 *   interruptible — there it's false.
 * @param eofOnStreamEnd whether `read < 0` counts as normal server EOF ([endedWithEof], e.g.
 *   `exit`); for serial, end of stream means the device disconnected, not "server closed the shell".
 */
internal abstract class StreamShellChannel(
    private val unblockReadOnCancel: Boolean,
    private val eofOnStreamEnd: Boolean = true,
) : ShellChannel {

    private val outputClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // Set by the [output] loop on reaching EOF (read<0). Transport drop / our own close() drop
    // the read as an IOException and leave this flag untouched.
    private val eofReached = AtomicBoolean(false)
    final override val endedWithEof: Boolean get() = eofReached.get()

    // Channel traffic counters (for the speed indicator): written from read/write IO threads,
    // read from a poller on another coroutine — AtomicLong for thread-safe visibility.
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    final override val bytesUp: Long get() = _bytesUp.get()
    final override val bytesDown: Long get() = _bytesDown.get()

    /** Blocking read of the next chunk; runs on Dispatchers.IO under runInterruptible. */
    protected abstract fun readBlocking(buffer: ByteArray): Int

    /** Close the source (socket/stream/port) — unblocks a hung [readBlocking]. Must not throw. */
    protected abstract fun closeSource()

    /**
     * Application bytes from a raw chunk (Telnet strips IAC negotiation here and sends replies
     * back). An empty result isn't emitted. Default: bytes as-is.
     */
    protected open suspend fun transform(chunk: ByteArray): ByteArray = chunk

    /** Counts outgoing traffic — call from the subclass's write path. */
    protected fun countBytesUp(n: Int) {
        _bytesUp.addAndGet(n.toLong())
    }

    final override val output: Flow<ByteArray> = flow {
        check(outputClaimed.compareAndSet(false, true)) {
            "ShellChannel.output supports only one collector"
        }
        val disposable = if (unblockReadOnCancel) {
            currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { closeSource() } }
        } else {
            null
        }
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = try {
                    runInterruptible(Dispatchers.IO) { readBlocking(buffer) }
                } catch (_: IOException) {
                    break
                }
                if (read < 0) {
                    if (eofOnStreamEnd) eofReached.set(true)
                    break
                }
                if (read == 0) continue
                _bytesDown.addAndGet(read.toLong())
                val data = transform(buffer.copyOf(read))
                if (data.isNotEmpty()) emit(data)
            }
        } finally {
            disposable?.dispose()
        }
    }

    /** Idempotent teardown: only the first call closes the source via [closeSource]. */
    final override suspend fun close() = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        closeSource()
        Unit
    }

    protected companion object {
        const val BUFFER_SIZE = 8192
    }
}
