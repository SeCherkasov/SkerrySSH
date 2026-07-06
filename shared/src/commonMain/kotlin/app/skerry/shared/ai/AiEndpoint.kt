package app.skerry.shared.ai

import app.skerry.shared.ai.local.LocalModel

/**
 * Where an AI request goes: an external OpenAI-compatible endpoint (BYOK) or an on-device local
 * model. Chosen by [AiRouter] from settings and per-host policy; the platform provider factory
 * builds an [AiProvider] from it ([app.skerry.shared.ai.OpenAiProvider] / `LocalAiProvider`).
 */
sealed interface AiEndpoint {
    /** Model identifier for [AiChatRequest.model]. */
    val requestModel: String

    data class Cloud(val config: OpenAiConfig) : AiEndpoint {
        override val requestModel: String get() = config.model
    }

    data class Device(val model: LocalModel) : AiEndpoint {
        override val requestModel: String get() = model.id
    }
}
