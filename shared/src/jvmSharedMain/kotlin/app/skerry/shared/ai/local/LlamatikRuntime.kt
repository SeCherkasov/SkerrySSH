package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.wire
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * [LocalLlmRuntime] over Llamatik (llama.cpp, GGUF), one implementation shared by desktop and
 * Android (the KMP artifact bundles both natives). `LlamaBridge` is a global singleton native
 * library with a single loaded model and global generation state, so:
 * - one runtime instance per process (the model stays resident between requests, reloaded only
 *   on file change);
 * - concurrent generations are serialized by [mutex], a second request waits rather than
 *   interfering with the first;
 * - cancelling the collector stops generation via `nativeCancelGenerate` on the next delta
 *   (the JNI call is blocking and cannot be interrupted from outside).
 *
 * Blocking work (GGUF load, inference) runs on [Dispatchers.IO]; deltas go through a
 * `channelFlow`, the Llamatik callback fires synchronously on that same IO thread.
 *
 * [contextLength] is set per platform at graph build time: desktop 4096, Android 2048 (a 4B
 * model's KV cache at large context is hundreds of MiB, which OOMs on phones).
 */
class LlamatikRuntime(
    private val contextLength: Int,
) : LocalLlmRuntime {

    private val mutex = Mutex()
    private var loadedPath: Path? = null

    override fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta> = channelFlow {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureLoaded(modelPath)
                    applyParams(request)
                    val prompt = renderPrompt(request.messages)
                    var failure: String? = null
                    LlamaBridge.generateStream(
                        prompt,
                        object : GenStream {
                            override fun onDelta(text: String) {
                                // Collector gone (cancelled): channel is closed, ask the native side to stop.
                                if (trySendBlocking(AiDelta(text)).isClosed) LlamaBridge.nativeCancelGenerate()
                            }

                            override fun onComplete() {}

                            override fun onError(message: String) {
                                failure = message
                            }
                        },
                    )
                    failure?.let { throw AiException(AiException.Kind.PROTOCOL, "Local model error: $it") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    throw e
                } catch (e: Exception) {
                    throw AiException(AiException.Kind.PROTOCOL, "Local inference failed: ${e.message}", e)
                }
            }
        }
    }

    /** Load the model if it isn't already loaded (or a different one is). */
    private fun ensureLoaded(path: Path) {
        if (loadedPath == path) return
        // initGenerateModel replaces any previously loaded model; the API has no separate unload.
        if (!LlamaBridge.initGenerateModel(path.toString())) {
            loadedPath = null
            throw AiException(AiException.Kind.INVALID_REQUEST, "Failed to load local model at $path")
        }
        loadedPath = path
    }

    /**
     * Generation params, applied before every request: temperature/maxOutputTokens come from
     * [AiChatRequest] (the terminal bar asks for low temperature since a command should be
     * deterministic, not creative; chat uses the default). The call is cheap, a native setter.
     */
    private fun applyParams(request: AiChatRequest) {
        LlamaBridge.updateGenerateParams(
            temperature = request.temperature?.toFloat() ?: DEFAULT_TEMPERATURE,
            maxTokens = request.maxOutputTokens ?: DEFAULT_MAX_TOKENS,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = contextLength,
            numThreads = threads(),
            useMmap = true, // GGUF is mmapped rather than read onto the heap: faster start, lower RAM
            flashAttention = false,
            batchSize = 256,
            gpuLayers = 0, // Llamatik's prebuilt natives are CPU-only (Metal only on macOS arm64)
        )
    }

    /**
     * Renders the prompt using the chat template embedded in the GGUF; falls back to ChatML
     * (the native format of the catalog's Qwen/Phi models) when there is no template.
     */
    private fun renderPrompt(messages: List<AiMessage>): String =
        LlamaBridge.applyChatTemplate(messages.map { it.role.wire to it.content }, addAssistantPrefix = true)
            ?: buildString {
                messages.forEach { m ->
                    append("<|im_start|>").append(m.role.wire).append('\n')
                    append(m.content).append("<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }

    /** Inference thread count: physical cores without hyperthreading is roughly cores/2, min 2. */
    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    private companion object {
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_MAX_TOKENS = 1024 // assistant replies are short (CMD/INFO, compact explanations)
    }
}
