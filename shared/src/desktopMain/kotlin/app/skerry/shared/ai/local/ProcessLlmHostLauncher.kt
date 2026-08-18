package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiException
import app.skerry.shared.process.isWindows
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the desktop inference host: a second process running [LlmHostMain] — a plain JVM on this
 * app's classpath in a development run, the app's own launcher in a packaged build (see
 * [LlmHostCommandLine]).
 *
 * The two talk over a Unix socket in a private temp directory rather than the child's stdin/stdout:
 * llama.cpp and the Llamatik binding both print to the standard streams, and a stray line there
 * would corrupt the protocol. stdout/stderr stay inherited, so native logs still reach the console.
 *
 * The child exits by itself when the socket closes, so it cannot outlive the app.
 */
class ProcessLlmHostLauncher(
    private val contextLength: Int,
    private val selfCommand: String? = ProcessHandle.current().info().command().orElse(null),
    private val classpath: String = System.getProperty("java.class.path").orEmpty(),
    private val heapMegabytes: Int = DEFAULT_HEAP_MB,
    /** How long the child gets to connect back. A parameter so the give-up path is testable. */
    private val startTimeoutMillis: Long = START_TIMEOUT_MILLIS,
) : LlmHostLauncher {

    override suspend fun launch(): LlmHostLink {
        // Written inside the block below, read here if that block never hands its value over:
        // `withContext` discards what it produced when a cancellation lands as it completes, and the
        // link is the only handle on a child process that is by then alive and holding a model.
        val built = AtomicReference<LlmHostLink?>()
        try {
            return start(built)
        } catch (e: Throwable) {
            // The whole teardown, not just close(): nothing here may replace the exception that says
            // why the start failed — least of all a cancellation the caller branches on. The
            // cancellation test reaches this arm with nothing built; what nothing drives is the
            // close itself, which needs accept() to succeed and the cancel to land together — a
            // seam in this class that would exist only to be faked.
            runCatching { built.getAndSet(null)?.let { withContext(NonCancellable) { it.close() } } }
            throw e
        }
    }

    private suspend fun start(built: AtomicReference<LlmHostLink?>): LlmHostLink = withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory("skerry-llm-")
        val socketPath = directory.resolve(SOCKET_NAME)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        // Held here rather than inside accept(): the catch below is the one place every failing path
        // passes through, including a cancellation that discards a connection already made.
        val accepted = AtomicReference<SocketChannel?>()
        var process: Process? = null
        try {
            server.bind(UnixDomainSocketAddress.of(socketPath))
            process = startChild(socketPath)
            val channel = accept(server, process, accepted) ?: fail(startFailure(process))
            StreamLlmHostLink(
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
            ) {
                // A host wedged in a native call may never act on a polite termination, and this is
                // the path taken exactly when it looks stuck, so escalate rather than leak it.
                process.destroy()
                if (!process.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                cleanUp(server, socketPath, directory)
            }.also(built::set)
        } catch (e: Throwable) {
            // runCatching like the rest of the teardown: a failure to kill the child must not
            // replace the exception that says why the start failed.
            closeUnclaimed(accepted)
            runCatching { process?.destroyForcibly() }
            cleanUp(server, socketPath, directory)
            throw e
        }
    }

    private fun startChild(socketPath: Path): Process = ProcessBuilder(
        LlmHostCommandLine.build(selfCommand, classpath, socketPath.toString(), contextLength, heapMegabytes),
    )
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .also { LlmHostCommandLine.scrubEnvironment(it.environment()) }
        .start()

    /**
     * Waits for the child to connect back; gives up early if it died instead. Publishes what it
     * accepted into [accepted] before returning it, because every way this can end without the
     * caller receiving it — the timeout, a cancellation discarding the value at either coroutine
     * boundary — leaves the socket open with no other reference to close it.
     */
    private suspend fun accept(
        server: ServerSocketChannel,
        process: Process,
        accepted: AtomicReference<SocketChannel?>,
    ): SocketChannel? = coroutineScope {
        withTimeoutOrNull(startTimeoutMillis.milliseconds) {
            val watchdog = launch {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
                runCatching { server.close() } // unblocks accept()
            }
            try {
                runCatching { runInterruptible(Dispatchers.IO) { server.accept().also(accepted::set) } }
                    // The watchdog closes the socket to unblock this when the child dies, and the
                    // timeout closes it by interrupting — those are the two ways out. Anything else
                    // is a fault of ours (no descriptors left, a temp dir we may not write) and must
                    // not be reported as a host that answered too slowly. Nothing drives that arm
                    // either — provoking it means exhausting the fd table, not writing a test.
                    .getOrElse { if (it is ClosedChannelException) null else throw it }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun closeUnclaimed(channel: AtomicReference<SocketChannel?>) {
        channel.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private fun cleanUp(server: ServerSocketChannel, socketPath: Path, directory: Path) {
        runCatching { server.close() }
        runCatching { socketPath.deleteIfExists() }
        runCatching { directory.deleteIfExists() }
    }

    private fun fail(reason: String): Nothing =
        throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host: $reason")

    /**
     * Why the child never connected. Died on a signal and timed out are different faults with the
     * same [AiException.Kind], and a native that meets an instruction this CPU has not got
     * (llamatik's AVX-512 build — see the pin in `gradle/libs.versions.toml`) shows up here as
     * `signal 4`. The UI renders the kind, not this text, so it reaches a run from a terminal, a
     * stack trace and the tests; making it reachable from a packaged build needs a log sink that
     * this app does not have.
     */
    private fun startFailure(process: Process): String {
        if (process.isAlive) return "the inference host did not answer in ${startTimeoutMillis}ms"
        val code = process.exitValue()
        // A child killed by a signal exits as 128 + the signal number — a Unix shell convention, and
        // only for real signal numbers: Windows exit codes and an ordinary `exit 130` land in the
        // same range without anything having signalled the process.
        val signal = (code - SIGNAL_EXIT_BASE).takeIf { !isWindows && code in SIGNAL_EXIT_RANGE }
        return "the inference host died on start-up, exit code $code" + signal?.let { " (signal $it)" }.orEmpty()
    }

    private companion object {
        const val SOCKET_NAME = "host.sock"
        const val START_TIMEOUT_MILLIS = 60_000L
        const val EXIT_GRACE_MILLIS = 2_000L
        const val SIGNAL_EXIT_BASE = 128
        val SIGNAL_EXIT_RANGE = (SIGNAL_EXIT_BASE + 1)..(SIGNAL_EXIT_BASE + 64)

        /** The host holds protocol strings only; the model itself is native, mmapped memory. */
        const val DEFAULT_HEAP_MB = 256
    }
}
