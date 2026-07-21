package app.skerry.shared.ai.local

import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of the isolated inference host on desktop: a bare JVM that owns llama.cpp and
 * nothing else. Started by [ProcessLlmHostLauncher], which passes the Unix socket to connect back
 * to and the context length; the protocol then runs over that socket ([LlmHostServer]).
 *
 * Native logging from llama.cpp keeps going to the inherited stdout/stderr — the protocol is on the
 * socket precisely so a chatty native library cannot corrupt it.
 */
object LlmHostMain {

    /**
     * @param args `--llm-host <socket-path> <context-length>` — the same flag the app checks before
     * building any UI, since a packaged build re-launches its own launcher to get a host.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        // AWT must never initialize here: the app process proves what happens when llama.cpp is
        // loaded next to it. Set before anything can touch a graphics class.
        System.setProperty("java.awt.headless", "true")
        val rest = if (LlmHostCommandLine.isHostRun(args)) args.drop(1) else args.toList()
        val socketPath = rest.getOrNull(0) ?: fail("usage: --llm-host <socket-path> <context-length>")
        val contextLength = rest.getOrNull(1)?.toIntOrNull() ?: fail("context length must be a number")

        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(Path.of(socketPath)))
            runBlocking {
                LlmHostServer.serve(
                    input = Channels.newInputStream(channel),
                    output = Channels.newOutputStream(channel),
                    runtime = LlamatikRuntime(contextLength),
                )
            }
        }
        // The app closed the socket: nothing left to serve. Exit hard — the native library keeps
        // non-daemon threads around and a plain return would leave the process hanging.
        exitProcess(0)
    }

    private fun fail(message: String): Nothing {
        System.err.println("skerry-llm-host: $message")
        exitProcess(2)
    }
}
