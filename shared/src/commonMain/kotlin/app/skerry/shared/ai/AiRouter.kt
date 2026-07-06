package app.skerry.shared.ai

import app.skerry.shared.ai.local.LocalModel

/** Result of routing an AI request: a concrete [AiEndpoint] or a block reason. */
sealed interface AiRoute {
    data class Use(val endpoint: AiEndpoint) : AiRoute
    data class Blocked(val reason: Reason) : AiRoute

    enum class Reason {
        /** External provider selected but no API key set. */
        CLOUD_NOT_CONFIGURED,

        /** Local model selected but not yet downloaded. */
        DEVICE_NOT_READY,

        /** Strict policy requires a local model but none is on the device. */
        STRICT_NEEDS_DEVICE,

        /** AI is disabled globally ([AiProviderKind.OFF]) — no route is possible. */
        AI_DISABLED,
    }
}

/**
 * Single point choosing the endpoint from per-host policy and [AiSettings], same principle as
 * [AiPolicyDecision].
 *
 * Rules:
 * - [AiProviderKind.OFF] disables AI globally: always [AiRoute.Blocked], overriding any per-host
 *   policy (even Strict with a downloaded model).
 * - Policy forbids cloud ([AiPolicyDecision.cloudAllowed] == false, i.e. Strict) restricts routing
 *   to the local model regardless of the configured provider.
 * - Cloud allowed: respects [AiSettings.provider]; a local model without a downloaded file or
 *   cloud without a key yields [AiRoute.Blocked] rather than a silent fallback.
 *
 * [AiPolicy.Off] never reaches here — controllers filter it via [AiPolicyDecision.aiEnabled].
 * [device] is the catalog model for [AiSettings.localModelId] (`null` if the id is not in the
 * catalog); [deviceInstalled] reports whether it's downloaded
 * ([app.skerry.shared.ai.local.LocalModelStore.isInstalled]).
 */
object AiRouter {
    fun route(
        decision: AiPolicyDecision,
        settings: AiSettings,
        device: LocalModel?,
        deviceInstalled: Boolean,
    ): AiRoute {
        if (settings.provider == AiProviderKind.OFF) return AiRoute.Blocked(AiRoute.Reason.AI_DISABLED)
        val ready = device != null && deviceInstalled
        if (!decision.cloudAllowed) {
            return if (ready) AiRoute.Use(AiEndpoint.Device(device)) else AiRoute.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE)
        }
        return when (settings.provider) {
            AiProviderKind.DEVICE ->
                if (ready) AiRoute.Use(AiEndpoint.Device(device)) else AiRoute.Blocked(AiRoute.Reason.DEVICE_NOT_READY)
            AiProviderKind.CLOUD, AiProviderKind.OFF ->
                if (settings.isConfigured) AiRoute.Use(AiEndpoint.Cloud(settings.toOpenAiConfig()))
                else AiRoute.Blocked(AiRoute.Reason.CLOUD_NOT_CONFIGURED)
        }
    }
}
