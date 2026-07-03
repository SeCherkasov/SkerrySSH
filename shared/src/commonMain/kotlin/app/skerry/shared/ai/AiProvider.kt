package app.skerry.shared.ai

import kotlinx.coroutines.flow.Flow

/** Роль сообщения в диалоге с моделью (как в chat-completions API). */
enum class AiRole { SYSTEM, USER, ASSISTANT }

/** Wire-имя роли — общее для chat-completions JSON (OpenAiProvider) и chat-шаблонов GGUF (LlamatikRuntime). */
val AiRole.wire: String
    get() = when (this) {
        AiRole.SYSTEM -> "system"
        AiRole.USER -> "user"
        AiRole.ASSISTANT -> "assistant"
    }

/** Одно сообщение диалога. [content] уже собран вызывающей стороной с учётом per-host политики. */
data class AiMessage(val role: AiRole, val content: String)

/**
 * Запрос чат-комплишена. [model] — идентификатор модели провайдера (напр. `gpt-4o-mini`).
 * [temperature]/[maxOutputTokens] — `null` значит «оставить дефолт провайдера».
 */
data class AiChatRequest(
    val model: String,
    val messages: List<AiMessage>,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/** Приращение стримингового ответа: очередной кусок текста ассистента. */
data class AiDelta(val text: String)

/**
 * Сырое подключение к внешней LLM (BYOK). Контракт в ядре, реализация платформенная
 * (JVM: Ktor). Это НЕ ассистент: провайдер не знает про per-host политику и не собирает
 * контекст — он лишь гоняет запрос к модели. Политику и подтверждение выполнения применяет
 * слой выше (контроллеры `ui/ai`); вывод модели всегда считается недоверенным источником.
 *
 * Ошибки сети/протокола/аутентификации сигнализируются [AiException].
 */
interface AiProvider {

    /**
     * Стриминговый чат-комплишн: эмитит [AiDelta] по мере генерации, завершается нормально
     * по концу ответа. Реализация может внутренне не стримить (эмитить один дельта-кусок) —
     * контракт от этого не зависит.
     */
    fun chat(request: AiChatRequest): Flow<AiDelta>

    /** Освобождает сетевые ресурсы (HTTP-клиент). */
    suspend fun close()
}

/** Ошибка AI-провайдера: сеть, аутентификация, лимиты, некорректный запрос или протокол. */
class AiException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, UNAUTHORIZED, RATE_LIMITED, INVALID_REQUEST, PROTOCOL }
}
