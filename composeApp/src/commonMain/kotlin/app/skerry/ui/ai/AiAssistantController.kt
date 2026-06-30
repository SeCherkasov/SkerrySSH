package app.skerry.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.OpenAiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Реплика диалога с ассистентом (для отрисовки ленты чата). */
data class AiTurn(val role: AiRole, val text: String)

/**
 * UI-контроллер AI-ассистента: держит [AiSettings] (BYOK), сохраняет их и гоняет чат через
 * [AiProvider]. Намеренно НЕ зависит от Vault напрямую — настройки подаются лямбдами
 * [persist]/[reload], поэтому контроллер тестируется без крипты. [providerFactory] создаёт
 * платформенный провайдер (desktop/android — [app.skerry.shared.ai.OpenAiProvider]).
 *
 * Вывод модели — недоверенный источник: этот слой лишь показывает ответ, но НЕ исполняет команды
 * (исполнение с подтверждением — отдельная фича, слайс политик).
 */
class AiAssistantController(
    initialSettings: AiSettings,
    private val persist: (AiSettings) -> Unit,
    private val providerFactory: (OpenAiConfig) -> AiProvider,
    private val scope: CoroutineScope,
    private val reload: () -> AiSettings = { initialSettings },
) {
    var settings by mutableStateOf(initialSettings); private set

    /** Лента диалога (user/assistant реплики). */
    val turns = mutableStateListOf<AiTurn>()

    /** Частичный ответ во время генерации; `null` — генерации нет. */
    var streaming by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    private var job: Job? = null
    // См. TerminalAiController: поколение защищает состояние нового запроса от finally отменённого старого.
    private var generation = 0

    val isConfigured: Boolean get() = settings.isConfigured

    /** Перечитать настройки из хранилища (после разблокировки vault). */
    fun refresh() { settings = reload() }

    /**
     * Построить контроллер терминального AI-бара под per-host [policy], разделяя провайдер/scope/настройки
     * с этим ассистентом (BYOK-ключ один на приложение). Настройки читаются лениво — свежие после [refresh].
     */
    fun terminalController(policy: AiPolicy): TerminalAiController =
        TerminalAiController(policy, settings = { settings }, providerFactory = providerFactory, scope = scope)

    /** Сохранить BYOK-настройки (ключ шифруется в vault на стороне [persist]). */
    fun save(apiKey: String, model: String, baseUrl: String) {
        val next = AiSettings(
            apiKey = apiKey.trim(),
            model = model.trim().ifBlank { AiSettings().model },
            baseUrl = baseUrl.trim().ifBlank { AiSettings().baseUrl },
        )
        persist(next)
        settings = next
    }

    /** Отправить запрос ассистенту. No-op, если занят/пусто/не настроено. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !settings.isConfigured) return
        turns.add(AiTurn(AiRole.USER, text))
        busy = true
        error = null
        streaming = ""
        val gen = ++generation
        val history = turns.map { AiMessage(it.role, it.text) }
        val messages = listOf(AiMessage(AiRole.SYSTEM, SYSTEM_PROMPT)) + history
        val config = settings.toOpenAiConfig()
        job = scope.launch {
            var provider: AiProvider? = null
            val sb = StringBuilder()
            try {
                provider = providerFactory(config)
                provider.chat(AiChatRequest(config.model, messages)).collect { delta ->
                    sb.append(delta.text)
                    streaming = sb.toString()
                }
                turns.add(AiTurn(AiRole.ASSISTANT, sb.toString()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                error = friendly(e)
            } catch (e: Exception) {
                error = "AI request failed: ${e.message}"
            } finally {
                provider?.let { runCatching { it.close() } }
                if (gen == generation) {
                    streaming = null
                    busy = false
                }
            }
        }
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

    private fun friendly(e: AiException): String = when (e.kind) {
        AiException.Kind.UNAUTHORIZED -> "Invalid API key — check it in AI settings."
        AiException.Kind.RATE_LIMITED -> "Rate limited by the provider. Try again shortly."
        AiException.Kind.NETWORK -> "Network error reaching the AI provider."
        AiException.Kind.INVALID_REQUEST -> "The provider rejected the request (check model/params)."
        AiException.Kind.PROTOCOL -> "Unexpected response from the AI provider."
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are Skerry's built-in assistant: a concise, expert helper for SSH, the shell, and " +
                "terminal workflows. Prefer short answers and ready-to-run commands. Never invent host credentials."
    }
}
