package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import kotlinx.coroutines.flow.Flow
import okio.Path

/**
 * Local inference engine: contract lives in the core, implementation is platform-specific
 * (`LlamatikRuntime` in jvmShared — llama.cpp behind a KMP binding). Isolates the concrete
 * binding so swapping engines (llama-server, a custom NDK module) doesn't touch the provider or UI.
 *
 * Implementations must:
 * - keep the model loaded between calls (GGUF loading takes seconds and gigabytes of RAM, so it
 *   shouldn't happen per request) and switch when [modelPath] changes;
 * - run blocking generation off the UI thread;
 * - signal failures via [app.skerry.shared.ai.AiException] and propagate cancellation.
 */
interface LocalLlmRuntime {
    fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta>
}
