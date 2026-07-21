package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val cancelDrainMillis: Long = CANCEL_DRAIN_MILLIS,
) : LocalLlmRuntime {

    private val mutex = Mutex()
    private var link: LlmHostLink? = null

    override fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta> = flow {
        mutex.withLock {
            val start = LlmHostProtocol.encode(LlmHostCommand.Generate(modelPath.toString(), request))
            // A host that quietly went away between requests is not a user-visible failure: the
            // command never reached it, so a fresh host can run the same request from scratch.
            val live = reuseOrLaunch(start) ?: startFresh(start)
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

    /** Starts a host and hands it [start]; a host that dies right away is released, not cached. */
    private suspend fun startFresh(start: String): LlmHostLink {
        val fresh = try {
            launcher.launch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host did not start: ${e.message}", e)
        }
        link = fresh
        try {
            fresh.send(start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            release() // it never took the request; leaving it cached would fail the next one too
            throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host died on start-up", e)
        }
        return fresh
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
        // A deadline alone cannot stop a read that is already blocked inside the OS, and this
        // cleanup runs uncancellable while holding [mutex] — so the watchdog closes the transport
        // instead, which is what actually releases the read. Without it, a wedged host would hold
        // the lock forever and local AI would stay dead for the rest of the session.
        val watchdog = CoroutineScope(Dispatchers.Default).launch {
            delay(cancelDrainMillis)
            runCatching { live.close() }
        }
        val drained = runCatching { drain(live) }.getOrDefault(false)
        watchdog.cancel()
        if (!drained) release()
    }

    /** Reads to the end of the cancelled answer; false if the host went away instead. */
    private suspend fun drain(live: LlmHostLink): Boolean {
        live.send(LlmHostProtocol.encode(LlmHostCommand.Cancel))
        while (true) {
            val line = live.receive() ?: return false
            val event = LlmHostProtocol.decodeEvent(line)
            if (event is LlmHostEvent.Done || event is LlmHostEvent.Failure) return true
        }
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
