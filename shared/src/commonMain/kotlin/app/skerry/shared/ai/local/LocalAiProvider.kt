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
 * [AiProvider] поверх локальной модели: тот же контракт, что у облачного BYOK-провайдера,
 * поэтому контроллеры (`AiStreamRunner`, ассистент, терминальный бар) не знают, где считается
 * ответ. Промпт не покидает устройство; вывод модели всё равно недоверенный источник —
 * политика подтверждения команд выше по стеку не меняется.
 *
 * Провайдер добавляет модель-специфичный [LocalModel.extraSystem] (напр. `/no_think` Qwen3)
 * и режет лидирующий `<think>`-блок из стрима ([ThinkTagFilter]) — парсер CMD/INFO получает
 * чистый ответ. [close] — no-op: [runtime] разделяется процессом (модель остаётся загруженной),
 * по образцу pooled-клиента OpenAiProvider.
 */
class LocalAiProvider(
    private val model: LocalModel,
    private val modelPath: Path,
    private val runtime: LocalLlmRuntime,
) : AiProvider {

    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        val filter = ThinkTagFilter() // на каждую коллекцию свой — flow можно собирать повторно
        runtime.generate(modelPath, request.copy(messages = withExtraSystem(request.messages))).collect { delta ->
            val out = filter.feed(delta.text)
            if (out.isNotEmpty()) emit(AiDelta(out))
        }
        val tail = filter.tail()
        if (tail.isNotEmpty()) emit(AiDelta(tail))
    }

    override suspend fun close() {} // рантайм — общий на процесс, живёт дольше провайдера

    /** Дописать [LocalModel.extraSystem] в системное сообщение (или создать его, если нет). */
    private fun withExtraSystem(messages: List<AiMessage>): List<AiMessage> {
        val extra = model.extraSystem ?: return messages
        val system = messages.firstOrNull { it.role == AiRole.SYSTEM }
            ?: return listOf(AiMessage(AiRole.SYSTEM, extra)) + messages
        return messages.map { m ->
            if (m === system) m.copy(content = "${m.content}\n$extra") else m
        }
    }
}
