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
 * Local AI subsystem assembled by the platform entry point (desktop `main` / Android
 * `MainActivity`): model store, downloader, and inference runtime. `null` in
 * [app.skerry.ui.AppDependencies.localAi] means the platform has no local AI (previews/mocks);
 * the UI shows the on-device card as inactive.
 */
class LocalAiDeps(
    val store: LocalModelStore,
    val downloader: ModelDownloader,
    val runtime: LocalLlmRuntime,
) {
    fun installed(model: LocalModel): Boolean = store.isInstalled(model)

    /** Download controller for AI settings (lives in the same scope as the AI controllers). */
    fun modelsController(scope: CoroutineScope): LocalModelController =
        LocalModelController(::installed, downloader::download, store::delete, scope)
}

/**
 * Provider factory for an [AiRouter] endpoint: cloud uses a pooled BYOK client, device uses the
 * local runtime. A device endpoint without [local] should never occur, since the router only
 * routes to it once a local model is installed.
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
