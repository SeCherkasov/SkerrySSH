package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol between the app and the isolated inference host (see [IsolatedLlmRuntime]): one
 * JSON object per line, commands app -> host, events host -> app. JSON escaping keeps a frame on a
 * single line even when model output contains newlines, so the reader can split on `\n`.
 *
 * The domain types ([AiChatRequest] and friends) stay serialization-free; the DTOs below are the
 * only place that knows the wire shape.
 */
sealed interface LlmHostCommand {
    /** Run one generation. A host handles one at a time; the caller serializes. */
    data class Generate(val modelPath: String, val request: AiChatRequest) : LlmHostCommand

    /** Stop the generation in flight. Ignored by the host when nothing is running. */
    data object Cancel : LlmHostCommand
}

sealed interface LlmHostEvent {
    /** Next chunk of assistant text. */
    data class Delta(val text: String) : LlmHostEvent

    /** Generation finished (including after a [LlmHostCommand.Cancel]). */
    data object Done : LlmHostEvent

    /** Generation failed; [kind] is carried over so the UI keeps its typed failure. */
    data class Failure(val kind: AiException.Kind, val message: String) : LlmHostEvent
}

object LlmHostProtocol {

    fun encode(command: LlmHostCommand): String = when (command) {
        is LlmHostCommand.Generate -> json.encodeToString(
            CommandDto.serializer(),
            CommandDto(
                type = GENERATE,
                modelPath = command.modelPath,
                model = command.request.model,
                messages = command.request.messages.map { MessageDto(it.role, it.content) },
                temperature = command.request.temperature,
                maxOutputTokens = command.request.maxOutputTokens,
            ),
        )
        LlmHostCommand.Cancel -> json.encodeToString(CommandDto.serializer(), CommandDto(type = CANCEL))
    }

    fun decodeCommand(line: String): LlmHostCommand {
        val dto = decode(CommandDto.serializer(), line)
        return when (dto.type) {
            CANCEL -> LlmHostCommand.Cancel
            GENERATE -> LlmHostCommand.Generate(
                modelPath = dto.modelPath ?: malformed("generate without a model path"),
                request = AiChatRequest(
                    model = dto.model ?: malformed("generate without a model id"),
                    messages = dto.messages.map { AiMessage(it.role, it.content) },
                    temperature = dto.temperature,
                    maxOutputTokens = dto.maxOutputTokens,
                ),
            )
            else -> malformed("unknown command '${dto.type}'")
        }
    }

    fun encode(event: LlmHostEvent): String = json.encodeToString(
        EventDto.serializer(),
        when (event) {
            is LlmHostEvent.Delta -> EventDto(type = DELTA, text = event.text)
            LlmHostEvent.Done -> EventDto(type = DONE)
            is LlmHostEvent.Failure -> EventDto(type = FAILURE, kind = event.kind, message = event.message)
        },
    )

    fun decodeEvent(line: String): LlmHostEvent {
        val dto = decode(EventDto.serializer(), line)
        return when (dto.type) {
            DELTA -> LlmHostEvent.Delta(dto.text.orEmpty())
            DONE -> LlmHostEvent.Done
            FAILURE -> LlmHostEvent.Failure(dto.kind ?: AiException.Kind.PROTOCOL, dto.message.orEmpty())
            else -> malformed("unknown event '${dto.type}'")
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, line: String): T =
        try {
            json.decodeFromString(serializer, line)
        } catch (e: Exception) {
            malformed("unreadable frame", e)
        }

    private fun malformed(what: String, cause: Throwable? = null): Nothing =
        throw AiException(AiException.Kind.PROTOCOL, "Local inference host: $what", cause)

    private const val GENERATE = "generate"
    private const val CANCEL = "cancel"
    private const val DELTA = "delta"
    private const val DONE = "done"
    private const val FAILURE = "failure"

    private val json = Json { encodeDefaults = false }

    @Serializable
    private data class MessageDto(val role: AiRole, val content: String)

    @Serializable
    private data class CommandDto(
        val type: String,
        @SerialName("path") val modelPath: String? = null,
        val model: String? = null,
        val messages: List<MessageDto> = emptyList(),
        val temperature: Double? = null,
        @SerialName("maxTokens") val maxOutputTokens: Int? = null,
    )

    @Serializable
    private data class EventDto(
        val type: String,
        val text: String? = null,
        val kind: AiException.Kind? = null,
        val message: String? = null,
    )
}
