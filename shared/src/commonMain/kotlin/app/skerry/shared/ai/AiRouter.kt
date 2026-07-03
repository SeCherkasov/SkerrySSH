package app.skerry.shared.ai

import app.skerry.shared.ai.local.LocalModel

/** Результат маршрутизации AI-запроса: конкретный [AiEndpoint] либо причина блокировки. */
sealed interface AiRoute {
    data class Use(val endpoint: AiEndpoint) : AiRoute
    data class Blocked(val reason: Reason) : AiRoute

    enum class Reason {
        /** Выбран внешний провайдер, но API-ключ не задан. */
        CLOUD_NOT_CONFIGURED,

        /** Выбрана локальная модель, но она ещё не скачана. */
        DEVICE_NOT_READY,

        /** Политика Strict требует локальную модель, а её нет на устройстве. */
        STRICT_NEEDS_DEVICE,
    }
}

/**
 * Единственная точка выбора эндпоинта из per-host политики и [AiSettings] (не размазывать
 * `when` по контроллерам — тот же принцип, что [AiPolicyDecision]).
 *
 * Правила:
 * - Политика запрещает облако ([AiPolicyDecision.cloudAllowed] == false, т.е. Strict) →
 *   ТОЛЬКО локальная модель, независимо от выбранного в настройках провайдера. «Strict —
 *   только локальный AI» из product brief становится рабочим, а не заглушкой.
 * - Облако разрешено → уважаем выбор пользователя ([AiSettings.provider]): локальная модель
 *   без скачанного файла или облако без ключа дают [AiRoute.Blocked], а не тихий fallback —
 *   пользователь явно видит, что настроить.
 *
 * [AiPolicy.Off] сюда не доходит — контроллеры отсекают его по [AiPolicyDecision.aiEnabled].
 * [device] — модель из каталога по [AiSettings.localModelId] (`null` — id не из каталога);
 * [deviceInstalled] — скачана ли она ([app.skerry.shared.ai.local.LocalModelStore.isInstalled]).
 */
object AiRouter {
    fun route(
        decision: AiPolicyDecision,
        settings: AiSettings,
        device: LocalModel?,
        deviceInstalled: Boolean,
    ): AiRoute {
        val ready = device != null && deviceInstalled
        if (!decision.cloudAllowed) {
            return if (ready) AiRoute.Use(AiEndpoint.Device(device)) else AiRoute.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE)
        }
        return when (settings.provider) {
            AiProviderKind.DEVICE ->
                if (ready) AiRoute.Use(AiEndpoint.Device(device)) else AiRoute.Blocked(AiRoute.Reason.DEVICE_NOT_READY)
            AiProviderKind.CLOUD ->
                if (settings.isConfigured) AiRoute.Use(AiEndpoint.Cloud(settings.toOpenAiConfig()))
                else AiRoute.Blocked(AiRoute.Reason.CLOUD_NOT_CONFIGURED)
        }
    }
}
