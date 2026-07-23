package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration.Companion.milliseconds

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
) : LlmHostLauncher {

    override suspend fun launch(): LlmHostLink = withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory("skerry-llm-")
        val socketPath = directory.resolve(SOCKET_NAME)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        var process: Process? = null
        try {
            server.bind(UnixDomainSocketAddress.of(socketPath))
            process = startChild(socketPath)
            val channel = accept(server, process) ?: fail("the inference host did not start")
            StreamLlmHostLink(
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
            ) {
                // A host wedged in a native call may never act on a polite termination, and this is
                // the path taken exactly when it looks stuck, so escalate rather than leak it.
                process.destroy()
                if (!process.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                cleanUp(server, socketPath, directory)
            }
        } catch (e: Throwable) {
            process?.destroyForcibly()
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

    /** Waits for the child to connect back; gives up early if it died instead. */
    private suspend fun accept(server: ServerSocketChannel, process: Process): SocketChannel? = coroutineScope {
        withTimeoutOrNull(START_TIMEOUT_MILLIS.milliseconds) {
            val watchdog = launch {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
                runCatching { server.close() } // unblocks accept()
            }
            try {
                runCatching { runInterruptible(Dispatchers.IO) { server.accept() } }.getOrNull()
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun cleanUp(server: ServerSocketChannel, socketPath: Path, directory: Path) {
        runCatching { server.close() }
        runCatching { socketPath.deleteIfExists() }
        runCatching { directory.deleteIfExists() }
    }

    private fun fail(reason: String): Nothing =
        throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host: $reason")

    private companion object {
        const val SOCKET_NAME = "host.sock"
        const val START_TIMEOUT_MILLIS = 60_000L
        const val EXIT_GRACE_MILLIS = 2_000L

        /** The host holds protocol strings only; the model itself is native, mmapped memory. */
        const val DEFAULT_HEAP_MB = 256
    }
}
