package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okio.Path.Companion.toPath
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The other side of [IsolatedLlmRuntime]: reads [LlmHostCommand]s and drives a real
 * [LocalLlmRuntime], reporting back as [LlmHostEvent]s. Runs inside the isolated host — a child JVM
 * on desktop, the `:llm` process on Android — so a native abort in llama.cpp takes down this loop
 * and nothing else.
 *
 * One generation at a time (the app serializes), but reading never stops: a `cancel` has to be
 * heard while an answer is streaming.
 */
object LlmHostServer {

    suspend fun serve(input: InputStream, output: OutputStream, runtime: LocalLlmRuntime) {
        // Explicit UTF-8: the app and the host may run with different platform defaults.
        val reader = input.bufferedReader(Charsets.UTF_8)
        val writer = output.writer(Charsets.UTF_8)
        val out = SerialWriter(writer)
        coroutineScope {
            var generation: Generation? = null
            while (true) {
                val line = runInterruptible(Dispatchers.IO) { reader.readLineOrNull() } ?: break
                when (val command = decode(line, out) ?: continue) {
                    LlmHostCommand.Cancel -> generation?.job?.cancel()
                    is LlmHostCommand.Generate -> {
                        // Every request gets exactly one closing frame, so a request that arrives
                        // while an answer is still running must not close the new one with the old
                        // one's `done`. The app serializes requests, but the protocol shouldn't
                        // desync if it ever stops.
                        generation?.supersede()
                        generation = Generation().also { it.job = launch { runGeneration(command, runtime, out, it) } }
                    }
                }
            }
            generation?.supersede()
        }
    }

    /** One answer in flight; [silenced] survives the cancellation that stops the job. */
    private class Generation {
        lateinit var job: Job
        var silenced = false
            private set

        suspend fun supersede() {
            silenced = true
            job.cancelAndJoin()
        }
    }

    private suspend fun runGeneration(
        command: LlmHostCommand.Generate,
        runtime: LocalLlmRuntime,
        out: SerialWriter,
        generation: Generation,
    ) {
        try {
            runtime.generate(command.modelPath.toPath(), command.request).collect {
                out.write(LlmHostEvent.Delta(it.text))
            }
            out.write(LlmHostEvent.Done)
        } catch (_: CancellationException) {
            // The app asked to stop: the answer is over, not failed — it expects the closing frame.
            if (!generation.silenced) out.write(LlmHostEvent.Done)
        } catch (e: AiException) {
            out.write(LlmHostEvent.Failure(e.kind, e.message.orEmpty()))
        } catch (e: Exception) {
            out.write(LlmHostEvent.Failure(AiException.Kind.PROTOCOL, "Local inference failed: ${e.message}"))
        }
    }

    /** A frame we cannot parse is reported, not acted on: staying silent would hang the app. */
    private fun decode(line: String, out: SerialWriter): LlmHostCommand? =
        try {
            LlmHostProtocol.decodeCommand(line)
        } catch (e: AiException) {
            out.write(LlmHostEvent.Failure(e.kind, e.message.orEmpty()))
            null
        }

    private fun BufferedReader.readLineOrNull(): String? = runCatching { readLine() }.getOrNull()

    /** Frames are written from the generation coroutine and from the reader loop — one at a time. */
    private class SerialWriter(private val writer: Writer) {
        private val lock = ReentrantLock()

        fun write(event: LlmHostEvent) = lock.withLock {
            runCatching {
                writer.write(LlmHostProtocol.encode(event))
                writer.write("\n")
                writer.flush()
            }
            Unit
        }
    }
}
