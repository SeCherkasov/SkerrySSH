package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path
import java.io.BufferedReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.Writer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The inference host as seen from the app side: commands in, events out. Uses real pipes because
 * the point of the host is that it survives on the other side of a process boundary.
 */
class LlmHostServerTest {

    private val request = AiChatRequest("local", listOf(AiMessage(AiRole.USER, "hi")))

    private class Harness(runtime: LocalLlmRuntime) : AutoCloseable {
        private val commands = PipedOutputStream()
        private val events = PipedInputStream()
        val writer: Writer = commands.writer()
        val reader: BufferedReader = events.bufferedReader()
        // Both pipe ends are connected before the host starts: a PipedInputStream that connects
        // after its writer is closed never learns about it and blocks forever.
        private val hostIn = PipedInputStream(commands)
        private val hostOut = PipedOutputStream(events)
        private val scope = CoroutineScope(Dispatchers.IO)
        val served: Job = scope.launch { LlmHostServer.serve(hostIn, hostOut, runtime) }

        fun send(command: LlmHostCommand) {
            writer.write(LlmHostProtocol.encode(command) + "\n")
            writer.flush()
        }

        fun next(): LlmHostEvent = LlmHostProtocol.decodeEvent(reader.readLine() ?: error("host closed the stream"))

        override fun close() {
            runCatching { writer.close() }
            scope.cancel()
        }
    }

    @Test
    fun `relays deltas and reports completion`() = runBlocking {
        val runtime = FakeRuntime(flow { emit(AiDelta("he")); emit(AiDelta("llo")) })
        Harness(runtime).use { host ->
            host.send(LlmHostCommand.Generate("/m.gguf", request))

            withTimeout(10.seconds) {
                assertEquals(LlmHostEvent.Delta("he"), host.next())
                assertEquals(LlmHostEvent.Delta("llo"), host.next())
                assertEquals(LlmHostEvent.Done, host.next())
            }
            assertEquals("/m.gguf", runtime.lastPath?.toString())
            assertEquals(request, runtime.lastRequest)
        }
    }

    @Test
    fun `reports a failure with its kind instead of dying`() = runBlocking {
        val runtime = FakeRuntime(flow { throw AiException(AiException.Kind.INVALID_REQUEST, "no such model") })
        Harness(runtime).use { host ->
            host.send(LlmHostCommand.Generate("/m.gguf", request))

            withTimeout(10.seconds) {
                assertEquals(
                    LlmHostEvent.Failure(AiException.Kind.INVALID_REQUEST, "no such model"),
                    host.next(),
                )
            }
        }
    }

    @Test
    fun `cancel stops the generation and the host keeps serving`() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            flow {
                emit(AiDelta("start"))
                try {
                    blocked.await() // never completes: the request is only ended by cancellation
                } finally {
                    cancelled.complete(Unit)
                }
            },
            flow { emit(AiDelta("after")) },
        )
        Harness(runtime).use { host ->
            host.send(LlmHostCommand.Generate("/m.gguf", request))
            withTimeout(10.seconds) { assertEquals(LlmHostEvent.Delta("start"), host.next()) }

            host.send(LlmHostCommand.Cancel)

            withTimeout(10.seconds) {
                cancelled.await()
                assertEquals(LlmHostEvent.Done, host.next())

                host.send(LlmHostCommand.Generate("/m.gguf", request))
                assertEquals(LlmHostEvent.Delta("after"), host.next())
                assertEquals(LlmHostEvent.Done, host.next())
            }
        }
    }

    @Test
    fun `stops when the app closes the command stream`() = runBlocking {
        val host = Harness(FakeRuntime(flow { }))
        host.writer.close()

        withTimeout(10.seconds) { host.served.join() }
        assertTrue(host.served.isCompleted)
    }

    private class FakeRuntime(private vararg val answers: Flow<AiDelta>) : LocalLlmRuntime {
        var lastPath: Path? = null
        var lastRequest: AiChatRequest? = null
        private var call = 0

        override fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta> {
            lastPath = modelPath
            lastRequest = request
            return answers[call++.coerceAtMost(answers.lastIndex)]
        }
    }
}
