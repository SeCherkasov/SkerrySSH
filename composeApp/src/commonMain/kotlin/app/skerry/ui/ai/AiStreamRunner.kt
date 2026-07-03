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

/** Человекочитаемое сообщение об ошибке AI-провайдера (общее для чата и терминального бара). */
internal fun AiException.friendlyMessage(): String = when (kind) {
    AiException.Kind.UNAUTHORIZED -> "Invalid API key — check it in AI settings."
    AiException.Kind.RATE_LIMITED -> "Rate limited by the provider. Try again shortly."
    AiException.Kind.NETWORK -> "Network error reaching the AI provider."
    AiException.Kind.INVALID_REQUEST -> "The provider rejected the request (check model/params)."
    AiException.Kind.PROTOCOL -> "Unexpected response from the AI provider."
}

/**
 * Общий прогон одного стримингового AI-запроса — жизненный цикл, одинаковый для
 * [AiAssistantController] и [TerminalAiController]: создать провайдер из [providerFactory]
 * по выбранному [AiEndpoint] (облако или локальная модель), прогнать `chat`, аккумулируя
 * дельты, и гарантированно закрыть провайдер.
 *
 * - [onDelta] получает НАКОПЛЕННЫЙ текст после каждой дельты (для поля streaming).
 * - [onComplete] — полный ответ по успешному завершению потока.
 * - [onError] — готовое сообщение для пользователя ([friendlyMessage] для [AiException]);
 *   [CancellationException] пробрасывается, а не маппится в ошибку.
 * - [onFinally] выполняется всегда (успех/ошибка/отмена) ПОСЛЕ закрытия провайдера; guard по
 *   поколению запроса (защита от job-reassignment race) остаётся на вызывающем — он владеет
 *   состоянием busy/streaming.
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
        // null — дефолт провайдера; терминальный бар просит низкую (команда, не творчество).
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
