package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiEndpoint
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Human-readable error message for an AI provider exception (shared by chat and the terminal bar). */
internal fun AiException.friendlyMessage(): String = when (kind) {
    AiException.Kind.UNAUTHORIZED -> "Invalid API key — check it in AI settings."
    AiException.Kind.RATE_LIMITED -> "Rate limited by the provider. Try again shortly."
    AiException.Kind.NETWORK -> "Network error reaching the AI provider."
    AiException.Kind.INVALID_REQUEST -> "The provider rejected the request (check model/params)."
    AiException.Kind.PROTOCOL -> "Unexpected response from the AI provider."
}

/**
 * Runs a single streaming AI request — the lifecycle shared by [AiAssistantController] and
 * [TerminalAiController]: create a provider via [providerFactory] for the chosen [AiEndpoint]
 * (cloud or local model), run `chat` accumulating deltas, and always close the provider.
 *
 * - [onDelta] receives the accumulated text after each delta.
 * - [onComplete] fires with the full reply on successful stream completion.
 * - [onError] receives a ready-to-show message ([friendlyMessage] for [AiException]);
 *   [CancellationException] is rethrown, not mapped to an error.
 * - [onFinally] always runs (success/error/cancel), after the provider is closed; generation
 *   guarding against job-reassignment races is the caller's responsibility.
 */
internal class AiStreamRunner(
    private val providerFactory: (AiEndpoint) -> AiProvider,
    private val scope: CoroutineScope,
) {
    fun launch(
        endpoint: AiEndpoint,
        messages: List<AiMessage>,
        onDelta: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        onFinally: () -> Unit,
        // null uses the provider default; the terminal bar requests a low value (command, not creative).
        temperature: Double? = null,
    ): Job = scope.launch {
        var provider: AiProvider? = null
        val sb = StringBuilder()
        try {
            provider = providerFactory(endpoint)
            provider.chat(AiChatRequest(endpoint.requestModel, messages, temperature = temperature)).collect { delta ->
                sb.append(delta.text)
                onDelta(sb.toString())
            }
            onComplete(sb.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            onError(e.friendlyMessage())
        } catch (e: Exception) {
            onError("AI request failed: ${e.message}")
        } finally {
            provider?.let { runCatching { it.close() } }
            onFinally()
        }
    }
}
