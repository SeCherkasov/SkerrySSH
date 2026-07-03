package app.skerry.ui.ai

import app.skerry.shared.ai.AiEndpoint
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.OpenAiProvider
import app.skerry.shared.ai.local.LocalAiProvider
import app.skerry.shared.ai.local.LocalLlmRuntime
import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelStore
import app.skerry.shared.ai.local.ModelDownloader
import kotlinx.coroutines.CoroutineScope

/**
 * Подсистема локального AI, собираемая платформенной точкой входа (desktop `main` /
 * Android `MainActivity`): дисковый стор моделей, загрузчик и рантайм инференса.
 * `null` в [app.skerry.ui.AppDependencies.localAi] — платформа без локального AI
 * (превью/моки) — UI показывает карточку «на устройстве» неактивной.
 */
class LocalAiDeps(
    val store: LocalModelStore,
    val downloader: ModelDownloader,
    val runtime: LocalLlmRuntime,
) {
    fun installed(model: LocalModel): Boolean = store.isInstalled(model)

    /** Контроллер закачек для настроек AI (живёт в том же scope, что и AI-контроллеры). */
    fun modelsController(scope: CoroutineScope): LocalModelController =
        LocalModelController(::installed, downloader::download, store::delete, scope)
}

/**
 * Фабрика провайдера по эндпоинту [AiRouter]-а: облако — pooled BYOK-клиент, устройство —
 * локальный рантайм. Device-эндпоинт без [local] невозможен: роутер не выдаёт его, когда
 * `localInstalled` вернул false (а без графа он всегда false) — поэтому требование не мягкое.
 */
fun aiProviderFactory(local: LocalAiDeps?): (AiEndpoint) -> AiProvider = { endpoint ->
    when (endpoint) {
        is AiEndpoint.Cloud -> OpenAiProvider.pooled(endpoint.config)
        is AiEndpoint.Device -> {
            val deps = requireNotNull(local) { "on-device AI endpoint routed without a local AI graph" }
            LocalAiProvider(endpoint.model, deps.store.path(endpoint.model), deps.runtime)
        }
    }
}
