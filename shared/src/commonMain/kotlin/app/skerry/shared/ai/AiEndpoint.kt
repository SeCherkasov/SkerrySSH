package app.skerry.shared.ai

import app.skerry.shared.ai.local.LocalModel

/**
 * Куда идёт AI-запрос: внешний OpenAI-совместимый endpoint (BYOK) или локальная модель на
 * устройстве. Выбирается [AiRouter] из настроек и per-host политики; платформенная фабрика
 * провайдера строит по нему [AiProvider] ([app.skerry.shared.ai.OpenAiProvider] /
 * `LocalAiProvider`).
 */
sealed interface AiEndpoint {
    /** Идентификатор модели для [AiChatRequest.model]. */
    val requestModel: String

    data class Cloud(val config: OpenAiConfig) : AiEndpoint {
        override val requestModel: String get() = config.model
    }

    data class Device(val model: LocalModel) : AiEndpoint {
        override val requestModel: String get() = model.id
    }
}
