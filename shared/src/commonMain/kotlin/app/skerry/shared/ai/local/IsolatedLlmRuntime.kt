package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path

/**
 * [LocalLlmRuntime] that runs inference in a separate process (see [LlmHostLauncher]) instead of
 * loading llama.cpp into the app. The native library aborts the whole process on some inputs and
 * corrupts memory when it is loaded after Skia/AWT (issue #37) — out of process, such a crash costs
 * one answer instead of every open SSH session, and the host is a clean headless JVM, which is the
 * configuration where the library behaves.
 *
 * The host stays alive between requests, so the GGUF is loaded once. Generations are serialized by
 * [mutex] — the host handles one at a time. A host that died is dropped and replaced: silently when
 * it went away between requests, with [AiException.Kind.ENGINE_CRASHED] when it died mid-answer.
 */
class IsolatedLlmRuntime(
    private val launcher: LlmHostLauncher,
) : LocalLlmRuntime {

    private val mutex = Mutex()
    private var link: LlmHostLink? = null

    override fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta> = flow {
        mutex.withLock {
            val start = LlmHostProtocol.encode(LlmHostCommand.Generate(modelPath.toString(), request))
            // A host that quietly went away between requests is not a user-visible failure: the
            // command never reached it, so a fresh host can run the same request from scratch.
            val live = reuseOrLaunch(start) ?: launchFresh().also { it.send(start) }
            stream(live)
        }
    }

    /** Shuts the host down; the next request starts a new one. */
    suspend fun close() {
        mutex.withLock { release() }
    }

    /** Sends [start] on the existing host, or returns null if there is none or it is already gone. */
    private suspend fun reuseOrLaunch(start: String): LlmHostLink? {
        val existing = link ?: return null
        return try {
            existing.send(start)
            existing
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            release()
            null
        }
    }

    private suspend fun launchFresh(): LlmHostLink = try {
        launcher.launch().also { link = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiException) {
        throw e
    } catch (e: Exception) {
        throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host did not start: ${e.message}", e)
    }

    /** Relays events until the host reports the end of the answer. */
    private suspend fun FlowCollector<AiDelta>.stream(live: LlmHostLink) {
        var finished = false
        try {
            while (true) {
                val line = live.receive() ?: crashed()
                when (val event = LlmHostProtocol.decodeEvent(line)) {
                    is LlmHostEvent.Delta -> emit(AiDelta(event.text))
                    LlmHostEvent.Done -> {
                        finished = true
                        return
                    }
                    is LlmHostEvent.Failure -> {
                        finished = true
                        throw AiException(event.kind, event.message)
                    }
                }
            }
        } finally {
            if (!finished) withContext(NonCancellable) { stopGeneration(live) }
        }
    }

    /**
     * The collector walked away mid-answer: tell the host to stop and wait for the tail of the
     * stream, so the next request starts on a quiet host. A host that ignores the cancel is not
     * trustworthy any more and gets replaced.
     */
    private suspend fun stopGeneration(live: LlmHostLink) {
        val drained = runCatching {
            withTimeoutOrNull(CANCEL_DRAIN_MILLIS) {
                live.send(LlmHostProtocol.encode(LlmHostCommand.Cancel))
                var tailSeen = false
                while (!tailSeen) {
                    val line = live.receive() ?: break
                    val event = LlmHostProtocol.decodeEvent(line)
                    tailSeen = event is LlmHostEvent.Done || event is LlmHostEvent.Failure
                }
                tailSeen
            }
        }.getOrNull()
        if (drained != true) release()
    }

    private suspend fun crashed(): Nothing {
        release()
        throw AiException(
            AiException.Kind.ENGINE_CRASHED,
            "Local inference host stopped before finishing the answer",
        )
    }

    private suspend fun release() {
        val gone = link ?: return
        link = null
        runCatching { gone.close() }
    }

    private companion object {
        /** Cancel is a flag polled between tokens, so the tail arrives fast or the host is stuck. */
        const val CANCEL_DRAIN_MILLIS = 5_000L
    }
}
