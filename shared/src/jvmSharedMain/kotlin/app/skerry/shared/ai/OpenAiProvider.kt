package app.skerry.shared.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JVM [AiProvider] for an OpenAI-compatible chat-completions API (desktop + Android), on Ktor.
 * BYOK: the key only goes into this request's `Authorization` header and is never logged.
 *
 * Real SSE streaming: the request sends `stream=true`, the response is read line by line from
 * the channel, and each model delta is emitted as a separate [AiDelta] as it's generated. The
 * HTTP status is checked before reading the body; SSE control lines (`:` comments, blank, `[DONE]`)
 * are skipped.
 *
 * Parsing assumption: each `data:` frame is a self-contained compact JSON object (as OpenAI sends).
 * Multi-line `data:` fields per the SSE spec (joined by `\n` until a blank line) are NOT supported —
 * an OpenAI-compatible endpoint that splits one chunk across multiple `data:` lines yields PROTOCOL.
 */
class OpenAiProvider private constructor(
    private val config: OpenAiConfig,
    private val http: HttpClient,
    private val ownsHttp: Boolean,
    /** Catalog-only client (never follows redirects, see [listModels]); falls back to the shared one. */
    private val catalogHttp: HttpClient? = null,
) : AiProvider {

    /** Shared (external) HttpClient — [close] does NOT close it; the caller owns the client. */
    constructor(config: OpenAiConfig, http: HttpClient, catalogHttp: HttpClient? = null) :
        this(config, http, ownsHttp = false, catalogHttp = catalogHttp)

    /** Creates and owns its own CIO client — [close] closes it. */
    constructor(config: OpenAiConfig) : this(config, defaultHttpClient(), ownsHttp = true)

    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        val wire = ChatReqWire(
            model = request.model,
            messages = request.messages.map { MsgWire(it.role.wire, it.content) },
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
            // emit() inside the execute block is safe as long as the block runs on the calling coroutine.
            // On JVM that's the Ktor <4.0 default (without io.ktor.client.statement.useEngineDispatcher).
            // Ktor 4.0 makes dispatcher switching the default — streaming here will then need channelFlow.
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
            throw e // coroutine cancellation is not a provider failure — never swallow or rewrap it
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            // Not just IOException: Ktor CIO also throws UnresolvedAddressException (non-IO) for a
            // bad host — any other failure is also NETWORK (mirrors KtorSyncClient.request).
            throw AiException(AiException.Kind.NETWORK, "AI request failed: ${e.message}", e)
        }
    }

    /**
     * Extracts the text delta from one SSE line. Returns `null` for control lines
     * (`:` comments/keep-alive, blank, non-`data:`, `[DONE]`). Throws [AiException.Kind.PROTOCOL]
     * on an invalid JSON frame.
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

    /**
     * Fetches the model catalog (`GET {baseUrl}/models`) for the BYOK settings refresh button. The
     * key is only used in this request's `Authorization` header, never logged.
     *
     * The catalog request never follows redirects: this is the first authenticated `GET` in the
     * client, and Ktor's default `HttpRedirect` would replay the `Authorization` header against
     * whatever host a 3xx `Location` names — a one-way ticket for the API key to an arbitrary host.
     * A 3xx is therefore surfaced as a PROTOCOL failure.
     *
     * The response is read with a hard cap and the catalog is trimmed (bounded size, bounded id
     * length, duplicates/blank dropped): the result lands in a cache file and a non-lazy list, so
     * a broken or hostile endpoint must not be able to inflate either.
     */
    override suspend fun listModels(): List<String> {
        val client = catalogHttp ?: noRedirectClient
        val response = client.get("${config.baseUrl}/models") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        if (!response.status.isSuccess()) throw errorFor(response.status)
        // Read with a hard cap. readAvailable fills as much as is currently available in one go,
        // so loop until EOF (or the cap) instead of trusting a single call to return the whole body
        // — a large or chunked body would otherwise be truncated mid-JSON.
        val buffer = ByteArray(MAX_CATALOG_BYTES + 1)
        val channel = response.bodyAsChannel()
        var size = 0
        while (size < buffer.size) {
            val read = channel.readAvailable(buffer, size, buffer.size - size)
            if (read <= 0) break // EOF
            size += read
        }
        if (size > MAX_CATALOG_BYTES) {
            throw AiException(AiException.Kind.PROTOCOL, "Model catalog response exceeds $MAX_CATALOG_BYTES bytes")
        }
        val body = buffer.decodeToString(0, size)
        return try {
            json.decodeFromString(ModelsWire.serializer(), body).data
                .asSequence()
                .map { it.id }
                .filter { it.isNotBlank() && it.length <= MAX_MODEL_ID_LENGTH }
                .distinct()
                .take(MAX_CATALOG_SIZE)
                .toList()
        } catch (e: Exception) {
            throw AiException(AiException.Kind.PROTOCOL, "Malformed model catalog", e)
        }
    }

    override suspend fun close() {
        if (ownsHttp) http.close()
    }

    private fun errorFor(status: HttpStatusCode): AiException = when (status.value) {
        // Redirects are not followed (see listModels) — an authenticated GET must never be replayed
        // against whatever host a 3xx Location names.
        in 300..399 -> AiException(AiException.Kind.PROTOCOL, "AI provider redirected the request ($status)")
        401, 403 -> AiException(AiException.Kind.UNAUTHORIZED, "AI provider rejected the API key ($status)")
        429 -> AiException(AiException.Kind.RATE_LIMITED, "AI provider rate limit ($status)")
        400, 404, 422 -> AiException(AiException.Kind.INVALID_REQUEST, "AI provider rejected the request ($status)")
        else -> AiException(AiException.Kind.PROTOCOL, "AI provider error ($status)")
    }

    companion object {
        /** Provider over a shared process-wide HttpClient (the CIO engine isn't recreated per request). */
        fun pooled(config: OpenAiConfig): OpenAiProvider = OpenAiProvider(config, shared)

        /**
         * Catalog-only shared client with redirects disabled: the model catalog is the one
         * authenticated `GET` in the client, so it must not follow a 3xx to another host with the
         * `Authorization` header attached (see [listModels]).
         */
        private val noRedirectClient: HttpClient by lazy {
            HttpClient(CIO) { followRedirects = false }
        }

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val shared: HttpClient by lazy { defaultHttpClient() }
        private fun defaultHttpClient(): HttpClient = HttpClient(CIO)

        /** Hard caps for the model catalog: the endpoint is remote and possibly broken or hostile. */
        const val MAX_CATALOG_SIZE = 2000
        const val MAX_MODEL_ID_LENGTH = 200
        const val MAX_CATALOG_BYTES = 1_048_576 // 1 MiB
    }
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

/** One SSE streaming frame: `choices[].delta.content` carries the next chunk of text. */
@Serializable
private data class ChatChunkWire(val choices: List<ChunkChoiceWire> = emptyList())

@Serializable
private data class ChunkChoiceWire(val delta: DeltaWire? = null)

@Serializable
private data class DeltaWire(val content: String? = null)

/** `GET /models` response: `{"data":[{"id":"…"},…]}`. */
@Serializable
private data class ModelsWire(val data: List<ModelWire> = emptyList())

@Serializable
private data class ModelWire(val id: String)
