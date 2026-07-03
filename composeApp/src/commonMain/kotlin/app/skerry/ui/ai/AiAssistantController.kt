package app.skerry.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiEndpoint
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiRoute
import app.skerry.shared.ai.AiRouter
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.SecretRedactor
import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Реплика диалога с ассистентом (для отрисовки ленты чата). */
data class AiTurn(val role: AiRole, val text: String)

/**
 * UI-контроллер AI-ассистента: держит [AiSettings] (выбор провайдера + BYOK + локальная модель),
 * сохраняет их и гоняет чат через [AiProvider]. Намеренно НЕ зависит от Vault напрямую —
 * настройки подаются лямбдами [persist]/[reload], поэтому контроллер тестируется без крипты.
 * [providerFactory] создаёт платформенный провайдер по [AiEndpoint] (облако —
 * [app.skerry.shared.ai.OpenAiProvider], устройство — `LocalAiProvider`); [localInstalled]
 * отвечает, скачана ли локальная модель на ЭТОМ устройстве (настройки синкаются, файл — нет).
 *
 * Вывод модели — недоверенный источник: этот слой лишь показывает ответ, но НЕ исполняет команды
 * (исполнение с подтверждением — отдельная фича, слайс политик).
 */
class AiAssistantController(
    initialSettings: AiSettings,
    private val persist: (AiSettings) -> Unit,
    private val providerFactory: (AiEndpoint) -> AiProvider,
    private val scope: CoroutineScope,
    private val reload: () -> AiSettings = { initialSettings },
    private val localInstalled: (LocalModel) -> Boolean = { false },
    /** Контроллер закачек локальных моделей для настроек AI; `null` — платформа без локального AI. */
    val models: LocalModelController? = null,
) {
    var settings by mutableStateOf(initialSettings); private set

    private val runner = AiStreamRunner(providerFactory, scope)

    /** Лента диалога (user/assistant реплики). */
    val turns = mutableStateListOf<AiTurn>()

    /** Частичный ответ во время генерации; `null` — генерации нет. */
    var streaming by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    private var job: Job? = null
    // См. TerminalAiController: поколение защищает состояние нового запроса от finally отменённого старого.
    private var generation = 0

    /** Настроен ли внешний (BYOK) провайдер — для строки статуса возле полей ключа. */
    val isConfigured: Boolean get() = settings.isConfigured

    /**
     * Включён ли AI вообще ([AiProviderKind.OFF] — глобальный kill-switch из настроек).
     * false → терминальный AI-бар не создаётся, BYOK/quick-chat в настройках скрыты.
     */
    val enabled: Boolean get() = settings.provider != AiProviderKind.OFF

    /** Готов ли ассистент отвечать выбранным провайдером (ключ есть / модель скачана). */
    val ready: Boolean get() = route() is AiRoute.Use

    /** Локальная модель из настроек (для карточки провайдера в UI). */
    val localModel: LocalModel get() = LocalModelCatalog.resolve(settings.localModelId)

    /**
     * Quick-chat глобален (host-контекста нет), политика к нему не применяется — маршрутизируем
     * как Balanced: облако разрешено, секреты вычищаются всегда (см. [ask]).
     */
    private fun route(): AiRoute {
        val device = localModel
        return AiRouter.route(QUICK_CHAT_DECISION, settings, device, localInstalled(device))
    }

    /**
     * Язык (англоязычное имя: «English»/«Russian»), на котором терминальный AI-бар должен писать
     * INFO/ASK — = язык интерфейса. Выставляется из корня UI по [app.skerry.ui.i18n.LocalAppLocale];
     * читается лениво при каждом запросе (см. [TerminalAiController.responseLanguage]), поэтому смена
     * языка в настройках подхватывается без пересоздания контроллеров.
     *
     * Намеренно публичный мутабельный `var`, а не параметр конструктора: контроллер создаётся при
     * старте приложения (до композиции), а актуальная локаль живёт в Compose-состоянии платформенных
     * корней UI (`ui/desktop/DesktopDesignApp.kt`, `ui/mobile/MobileDesignApp.kt`) — они и присваивают
     * лямбду уже из композиции. Перенос в конструктор потребовал бы пересоздавать контроллер на смену
     * языка (потеряли бы ленту диалога).
     */
    var uiLanguageProvider: () -> String = { "English" }

    /** Перечитать настройки из хранилища (после разблокировки vault). */
    fun refresh() { settings = reload() }

    /**
     * Построить контроллер терминального AI-бара под per-host [policy], разделяя провайдер/scope/настройки
     * с этим ассистентом (BYOK-ключ один на приложение). Настройки читаются лениво — свежие после [refresh].
     */
    fun terminalController(policy: AiPolicy): TerminalAiController =
        TerminalAiController(
            policy,
            settings = { settings },
            providerFactory = providerFactory,
            scope = scope,
            responseLanguage = { uiLanguageProvider() },
            localInstalled = localInstalled,
        )

    /** Сохранить BYOK-поля (ключ шифруется в vault на стороне [persist]); выбор провайдера не трогаем. */
    fun save(apiKey: String, model: String, baseUrl: String) {
        persistSettings(
            settings.copy(
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { AiSettings().model },
                baseUrl = baseUrl.trim().ifBlank { AiSettings().baseUrl },
            ),
        )
    }

    /** Выбрать провайдера по умолчанию (карточки в настройках AI); сохраняется сразу. */
    fun selectProvider(kind: AiProviderKind) {
        persistSettings(settings.copy(provider = kind))
    }

    /** Выбрать локальную модель из каталога; сохраняется сразу. */
    fun selectLocalModel(id: String) {
        persistSettings(settings.copy(localModelId = id))
    }

    private fun persistSettings(next: AiSettings) {
        persist(next)
        settings = next
    }

    /**
     * Отправить запрос ассистенту. No-op, если занят/пусто/провайдер не готов ([ready]).
     *
     * Quick-chat глобален — host-контекста нет, поэтому per-host [AiPolicy] не применить; секреты
     * вычищаются ВСЕГДА (эквивалент [AiPolicy.Balanced], см. [SecretRedactor]) — даже для
     * локальной модели: лента и переиспользуемая история едины для обоих провайдеров.
     * Редактируем до записи в [turns]: пользователь видит ровно то, что ушло провайдеру,
     * и история для последующих запросов уже чистая.
     */
    fun ask(prompt: String) {
        val text = SecretRedactor.redact(prompt.trim())
        val route = route()
        if (busy || text.isEmpty() || route !is AiRoute.Use) return
        turns.add(AiTurn(AiRole.USER, text))
        busy = true
        error = null
        streaming = ""
        val gen = ++generation
        val history = turns.map { AiMessage(it.role, it.text) }
        val messages = listOf(AiMessage(AiRole.SYSTEM, SYSTEM_PROMPT)) + history
        job = runner.launch(
            endpoint = route.endpoint,
            messages = messages,
            onDelta = { streaming = it },
            onComplete = { turns.add(AiTurn(AiRole.ASSISTANT, it)) },
            onError = { error = it },
            onFinally = {
                if (gen == generation) {
                    streaming = null
                    busy = false
                }
            },
        )
    }

    /** Отменить текущий запрос (если идёт) и очистить ленту. */
    fun clearConversation() {
        generation++
        job?.cancel()
        turns.clear()
        error = null
        streaming = null
        busy = false
    }

    private companion object {
        /** Разрешения quick-chat: как Balanced — облако можно, секреты вычищаются. */
        val QUICK_CHAT_DECISION = AiPolicyDecision.of(AiPolicy.Balanced)

        const val SYSTEM_PROMPT =
            "You are Skerry's built-in assistant: a concise, expert helper for SSH, the shell, and " +
                "terminal workflows. Prefer short answers and ready-to-run commands. Never invent host credentials."
    }
}
