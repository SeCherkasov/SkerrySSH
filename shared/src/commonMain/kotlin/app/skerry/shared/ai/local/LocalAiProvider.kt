package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.Path

/**
 * [AiProvider] over a local model: same contract as the cloud BYOK provider, so controllers
 * (`AiStreamRunner`, assistant, terminal bar) don't know where the response is computed. The
 * prompt never leaves the device; model output is still an untrusted source — the command
 * confirmation policy further up the stack is unchanged.
 *
 * The provider appends the model-specific [LocalModel.extraSystem] (e.g. Qwen3's `/no_think`)
 * and strips a leading `<think>` block from the stream ([ThinkTagFilter]), so the CMD/INFO parser
 * gets a clean response. [close] is a no-op: [runtime] is shared per process (model stays loaded),
 * same pattern as OpenAiProvider's pooled client.
 */
class LocalAiProvider(
    private val model: LocalModel,
    private val modelPath: Path,
    private val runtime: LocalLlmRuntime,
) : AiProvider {

    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        val filter = ThinkTagFilter() // fresh per collection — the flow can be collected repeatedly
        runtime.generate(modelPath, request.copy(messages = withExtraSystem(request.messages))).collect { delta ->
            val out = filter.feed(delta.text)
            if (out.isNotEmpty()) emit(AiDelta(out))
        }
        val tail = filter.tail()
        if (tail.isNotEmpty()) emit(AiDelta(tail))
    }

    override suspend fun close() {} // runtime is shared per process, outlives the provider

    /** Appends [LocalModel.extraSystem] to the system message (or creates one if absent). */
    private fun withExtraSystem(messages: List<AiMessage>): List<AiMessage> {
        val extra = model.extraSystem ?: return messages
        val system = messages.firstOrNull { it.role == AiRole.SYSTEM }
            ?: return listOf(AiMessage(AiRole.SYSTEM, extra)) + messages
        return messages.map { m ->
            if (m === system) m.copy(content = "${m.content}\n$extra") else m
        }
    }
}
