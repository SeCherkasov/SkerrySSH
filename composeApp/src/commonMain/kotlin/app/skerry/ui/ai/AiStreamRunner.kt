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

/**
 * Why an AI request failed. Typed on purpose: controllers never build user-visible text, the UI
 * resolves it via `aiFailureMessage`. [UNKNOWN] covers non-[AiException] failures — the raw library
 * message is not shown to the user.
 */
enum class AiFailure {
    UNAUTHORIZED,
    RATE_LIMITED,
    NETWORK,
    INVALID_REQUEST,
    PROTOCOL,
    UNKNOWN,
}

/** Maps a provider exception to the typed UI failure. */
internal fun AiException.toFailure(): AiFailure = when (kind) {
    AiException.Kind.UNAUTHORIZED -> AiFailure.UNAUTHORIZED
    AiException.Kind.RATE_LIMITED -> AiFailure.RATE_LIMITED
    AiException.Kind.NETWORK -> AiFailure.NETWORK
    AiException.Kind.INVALID_REQUEST -> AiFailure.INVALID_REQUEST
    AiException.Kind.PROTOCOL -> AiFailure.PROTOCOL
}

/**
 * Runs a single streaming AI request — the lifecycle shared by [AiAssistantController] and
 * [TerminalAiController]: create a provider via [providerFactory] for the chosen [AiEndpoint]
 * (cloud or local model), run `chat` accumulating deltas, and always close the provider.
 *
 * - [onDelta] receives the accumulated text after each delta.
 * - [onComplete] fires with the full reply on successful stream completion.
 * - [onError] receives a typed [AiFailure] the UI turns into localized text;
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
        onError: (AiFailure) -> Unit,
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
            onError(e.toFailure())
        } catch (_: Exception) {
            // Library messages are not user-facing text: report a generic typed failure.
            onError(AiFailure.UNKNOWN)
        } finally {
            provider?.let { runCatching { it.close() } }
            onFinally()
        }
    }
}
