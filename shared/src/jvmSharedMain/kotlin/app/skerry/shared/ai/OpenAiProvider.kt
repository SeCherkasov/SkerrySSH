package app.skerry.shared.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * JVM-реализация [AiProvider] для OpenAI-совместимого chat-completions API (desktop + Android),
 * на Ktor. BYOK: ключ уходит только в заголовок `Authorization` этого запроса и нигде не логируется.
 *
 * Слайс 1 — без стриминга: запрос идёт с `stream=false`, весь ответ приходит разом и эмитится
 * одним [AiDelta]. Контракт стриминговый ([Flow]), поэтому переход на настоящий SSE (слайс 1b)
 * не затронет вызывающие стороны.
 */
class OpenAiProvider private constructor(
    private val config: OpenAiConfig,
    private val http: HttpClient,
    private val ownsHttp: Boolean,
) : AiProvider {

    /** Общий (внешний) HttpClient — [close] его НЕ закрывает: владелец клиента — вызывающий. */
    constructor(config: OpenAiConfig, http: HttpClient) : this(config, http, ownsHttp = false)

    /** Создаёт и владеет собственным CIO-клиентом — [close] его закрывает. */
    constructor(config: OpenAiConfig) : this(config, defaultHttpClient(), ownsHttp = true)

    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        val wire = ChatReqWire(
            model = request.model,
            messages = request.messages.map { MsgWire(it.role.wire(), it.content) },
            temperature = request.temperature,
            maxTokens = request.maxOutputTokens,
        )
        val response = try {
            http.post("${config.baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ChatReqWire.serializer(), wire))
            }
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, "AI request failed: ${e.message}", e)
        }
        if (!response.status.isSuccess()) throw errorFor(response.status)
        val parsed = try {
            json.decodeFromString(ChatRespWire.serializer(), response.bodyAsText())
        } catch (e: Exception) {
            throw AiException(AiException.Kind.PROTOCOL, "Malformed AI response", e)
        }
        val text = parsed.choices.firstOrNull()?.message?.content.orEmpty()
        if (text.isNotEmpty()) emit(AiDelta(text))
    }

    override suspend fun close() {
        if (ownsHttp) http.close()
    }

    private fun errorFor(status: HttpStatusCode): AiException = when (status.value) {
        401, 403 -> AiException(AiException.Kind.UNAUTHORIZED, "AI provider rejected the API key ($status)")
        429 -> AiException(AiException.Kind.RATE_LIMITED, "AI provider rate limit ($status)")
        400, 404, 422 -> AiException(AiException.Kind.INVALID_REQUEST, "AI provider rejected the request ($status)")
        else -> AiException(AiException.Kind.PROTOCOL, "AI provider error ($status)")
    }

    companion object {
        /** Провайдер поверх общего процесс-широкого HttpClient (CIO-движок не пересоздаётся на запрос). */
        fun pooled(config: OpenAiConfig): OpenAiProvider = OpenAiProvider(config, shared)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val shared: HttpClient by lazy { defaultHttpClient() }
        private fun defaultHttpClient(): HttpClient = HttpClient(CIO)
    }
}

private fun AiRole.wire(): String = when (this) {
    AiRole.SYSTEM -> "system"
    AiRole.USER -> "user"
    AiRole.ASSISTANT -> "assistant"
}

@Serializable
private data class ChatReqWire(
    val model: String,
    val messages: List<MsgWire>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
private data class MsgWire(val role: String, val content: String)

@Serializable
private data class ChatRespWire(val choices: List<ChoiceWire> = emptyList())

@Serializable
private data class ChoiceWire(val message: MsgWire? = null)
