package app.skerry.shared.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JVM-реализация [AiProvider] для OpenAI-совместимого chat-completions API (desktop + Android),
 * на Ktor. BYOK: ключ уходит только в заголовок `Authorization` этого запроса и нигде не логируется.
 *
 * Слайс 1b — настоящий SSE: запрос идёт с `stream=true`, ответ читается построчно из канала и
 * каждая дельта модели эмитится отдельным [AiDelta] по мере генерации. Ошибочный HTTP-статус
 * проверяется до чтения тела; служебные строки SSE (`:`-комментарии, пустые, `[DONE]`) пропускаются.
 *
 * Допущение парсинга: каждый `data:`-кадр — самостоятельный компактный JSON (как шлёт OpenAI).
 * Многострочные `data:`-поля SSE-спеки (склейка через `\n` до пустой строки) НЕ поддерживаются —
 * OpenAI-совместимый endpoint, дробящий один chunk на несколько `data:`-строк, даст PROTOCOL.
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
            stream = true,
        )
        val statement = http.preparePost("${config.baseUrl}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ChatReqWire.serializer(), wire))
        }
        try {
            // emit() внутри execute-блока безопасен, пока блок выполняется на вызывающей корутине.
            // На JVM это дефолт Ktor <4.0 (без io.ktor.client.statement.useEngineDispatcher). В Ktor 4.0
            // переключение диспетчера станет дефолтным — тогда стриминг тут надо унести в channelFlow.
            statement.execute { response ->
                if (!response.status.isSuccess()) throw errorFor(response.status)
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readLine() ?: break
                    val delta = contentDelta(line) ?: continue
                    if (delta.isNotEmpty()) emit(AiDelta(delta))
                }
            }
        } catch (e: CancellationException) {
            throw e // отмена корутины — не сбой провайдера, глотать/переупаковывать её нельзя
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            // Не только IOException: Ktor CIO кидает и UnresolvedAddressException (не-IO) на кривой
            // хост — любой прочий сбой тоже NETWORK (по образцу KtorSyncClient.request).
            throw AiException(AiException.Kind.NETWORK, "AI request failed: ${e.message}", e)
        }
    }

    /**
     * Извлекает текстовую дельту из одной строки SSE. Возвращает `null` для служебных строк
     * (`:`-комментарии/keep-alive, пустые, не-`data:`, `[DONE]`). Кидает [AiException.Kind.PROTOCOL]
     * на невалидный JSON-кадр.
     */
    private fun contentDelta(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.substring("data:".length).trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val chunk = try {
            json.decodeFromString(ChatChunkWire.serializer(), payload)
        } catch (e: Exception) {
            throw AiException(AiException.Kind.PROTOCOL, "Malformed AI stream chunk", e)
        }
        return chunk.choices.firstOrNull()?.delta?.content
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

/** Один SSE-кадр стриминга: `choices[].delta.content` несёт очередной кусок текста. */
@Serializable
private data class ChatChunkWire(val choices: List<ChunkChoiceWire> = emptyList())

@Serializable
private data class ChunkChoiceWire(val delta: DeltaWire? = null)

@Serializable
private data class DeltaWire(val content: String? = null)
