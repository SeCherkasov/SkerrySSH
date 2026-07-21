package app.skerry.shared.ai

import kotlinx.coroutines.flow.Flow

/** Role of a message in the model conversation (as in the chat-completions API). */
enum class AiRole { SYSTEM, USER, ASSISTANT }

/** Wire name of the role, shared by chat-completions JSON (OpenAiProvider) and GGUF chat templates (LlamatikRuntime). */
val AiRole.wire: String
    get() = when (this) {
        AiRole.SYSTEM -> "system"
        AiRole.USER -> "user"
        AiRole.ASSISTANT -> "assistant"
    }

/** One conversation message. [content] is already assembled by the caller per per-host policy. */
data class AiMessage(val role: AiRole, val content: String)

/**
 * Chat-completion request. [model] is the provider's model identifier (e.g. `gpt-4o-mini`).
 * [temperature]/[maxOutputTokens] `null` means "use the provider default".
 */
data class AiChatRequest(
    val model: String,
    val messages: List<AiMessage>,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/** One increment of a streamed response: the next chunk of assistant text. */
data class AiDelta(val text: String)

/**
 * Raw connection to an external LLM (BYOK). Contract in the core, implementation is platform-specific
 * (JVM: Ktor). This is NOT an assistant: the provider doesn't know about per-host policy and doesn't
 * assemble context — it only runs the request against the model. Policy and execution confirmation
 * are applied by the layer above (`ui/ai` controllers); model output is always treated as untrusted.
 *
 * Network/protocol/auth errors are signaled via [AiException].
 */
interface AiProvider {

    /**
     * Streaming chat completion: emits [AiDelta] as generation proceeds, completes normally at the
     * end of the response. An implementation may not stream internally (emit a single delta chunk) —
     * the contract doesn't depend on it.
     */
    fun chat(request: AiChatRequest): Flow<AiDelta>

    /** Releases network resources (HTTP client). */
    suspend fun close()
}

/** AI provider error: network, auth, rate limit, invalid request, protocol, or a dead local engine. */
class AiException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** [ENGINE_CRASHED] is local-only: the isolated inference host died (see `IsolatedLlmRuntime`). */
    enum class Kind { NETWORK, UNAUTHORIZED, RATE_LIMITED, INVALID_REQUEST, PROTOCOL, ENGINE_CRASHED }
}
