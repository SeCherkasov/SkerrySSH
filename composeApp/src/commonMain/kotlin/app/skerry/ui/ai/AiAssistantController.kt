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

/** A single turn in the assistant conversation (for rendering the chat feed). */
data class AiTurn(val role: AiRole, val text: String)

/**
 * UI controller for the AI assistant: holds [AiSettings] (provider choice + BYOK + local model),
 * persists them, and runs chat through [AiProvider]. Does not depend on Vault directly; settings
 * are supplied via [persist]/[reload] lambdas. [providerFactory] creates the platform provider for
 * an [AiEndpoint] (cloud - [app.skerry.shared.ai.OpenAiProvider], device - `LocalAiProvider`);
 * [localInstalled] reports whether the local model is downloaded on this device (settings sync,
 * the model file does not).
 *
 * Model output is an untrusted source: this layer only displays the reply, it does not execute commands.
 */
class AiAssistantController(
    initialSettings: AiSettings,
    private val persist: (AiSettings) -> Unit,
    private val providerFactory: (AiEndpoint) -> AiProvider,
    private val scope: CoroutineScope,
    private val reload: () -> AiSettings = { initialSettings },
    private val localInstalled: (LocalModel) -> Boolean = { false },
    /** Controller for local model downloads in AI settings; `null` on platforms without local AI. */
    val models: LocalModelController? = null,
) {
    var settings by mutableStateOf(initialSettings); private set

    private val runner = AiStreamRunner(providerFactory, scope)

    /** Conversation feed (user/assistant turns). */
    val turns = mutableStateListOf<AiTurn>()

    /** Partial reply while streaming; `null` when not generating. */
    var streaming by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    private var job: Job? = null
    // See TerminalAiController: generation guards new-request state from a cancelled old request's finally.
    private var generation = 0

    /** Whether an external (BYOK) provider is configured, for the status line near the key fields. */
    val isConfigured: Boolean get() = settings.isConfigured

    /**
     * Whether AI is enabled at all ([AiProviderKind.OFF] is the global kill switch). False hides
     * the terminal AI bar and the BYOK/quick-chat settings.
     */
    val enabled: Boolean get() = settings.provider != AiProviderKind.OFF

    /** Whether the assistant can respond with the selected provider (key set / model downloaded). */
    val ready: Boolean get() = route() is AiRoute.Use

    /** Local model from settings (for the provider card in the UI). */
    val localModel: LocalModel get() = LocalModelCatalog.resolve(settings.localModelId)

    /**
     * Quick-chat is global (no host context) so no policy applies; routed as Balanced: cloud
     * allowed, secrets always stripped (see [ask]).
     */
    private fun route(): AiRoute {
        val device = localModel
        return AiRouter.route(QUICK_CHAT_DECISION, settings, device, localInstalled(device))
    }

    /**
     * Language (English name: "English"/"Russian") the terminal AI bar should use for INFO/ASK
     * output, matching the UI locale. Set from the UI root via [app.skerry.ui.i18n.LocalAppLocale]
     * and read lazily per request (see [TerminalAiController.responseLanguage]), so a locale change
     * is picked up without recreating controllers.
     *
     * A mutable `var` rather than a constructor parameter because the controller is created before
     * composition, while the locale lives in Compose state of the platform UI roots.
     */
    var uiLanguageProvider: () -> String = { "English" }

    /** Reloads settings from storage (after vault unlock). */
    fun refresh() { settings = reload() }

    /**
     * Builds a terminal AI bar controller for a per-host [policy], sharing provider/scope/settings
     * with this assistant (one BYOK key per app). Settings are read lazily, fresh after [refresh].
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

    /** Saves BYOK fields (the key is encrypted in the vault by [persist]); provider choice is untouched. */
    fun save(apiKey: String, model: String, baseUrl: String) {
        persistSettings(
            settings.copy(
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { AiSettings().model },
                baseUrl = baseUrl.trim().ifBlank { AiSettings().baseUrl },
            ),
        )
    }

    /** Selects the default provider (AI settings cards); persisted immediately. */
    fun selectProvider(kind: AiProviderKind) {
        persistSettings(settings.copy(provider = kind))
    }

    /** Selects a local model from the catalog; persisted immediately. */
    fun selectLocalModel(id: String) {
        persistSettings(settings.copy(localModelId = id))
    }

    private fun persistSettings(next: AiSettings) {
        persist(next)
        settings = next
    }

    /**
     * Sends a prompt to the assistant. No-op if busy, empty, or the provider isn't ready ([ready]).
     *
     * Quick-chat is global (no host context), so no per-host [AiPolicy] applies; secrets are always
     * stripped (equivalent to [AiPolicy.Balanced], see [SecretRedactor]), even for the local model,
     * since the feed and history are shared across providers. Redaction happens before writing to
     * [turns], so the user sees exactly what was sent and later requests reuse clean history.
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

    /** Cancels the current request (if any) and clears the feed. */
    fun clearConversation() {
        generation++
        job?.cancel()
        turns.clear()
        error = null
        streaming = null
        busy = false
    }

    private companion object {
        /** Quick-chat permissions: same as Balanced — cloud allowed, secrets stripped. */
        val QUICK_CHAT_DECISION = AiPolicyDecision.of(AiPolicy.Balanced)

        const val SYSTEM_PROMPT =
            "You are Skerry's built-in assistant: a concise, expert helper for SSH, the shell, and " +
                "terminal workflows. Prefer short answers and ready-to-run commands. Never invent host credentials."
    }
}
