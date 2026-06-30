package app.skerry.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.CommandAssessment
import app.skerry.shared.ai.CommandRiskClassifier
import app.skerry.shared.ai.OpenAiConfig
import app.skerry.shared.ai.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Контроллер терминального AI-бара: превращает запрос на естественном языке в ОДНУ shell-команду
 * под per-host политикой [AiPolicy] (принцип «AI under policy»).
 *
 * Инварианты безопасности:
 * - **Подтверждение перед выполнением — всегда.** [ask] лишь кладёт предложенную команду в [pending];
 *   исполнить (вставить в ввод терминала) её можно только через явный [confirm]. Автозапуска нет
 *   ни при какой политике — вывод модели считается недоверенным.
 * - Политика решает доступность облака и санитизацию ([AiPolicyDecision]): [AiPolicy.Off] — бар скрыт;
 *   [AiPolicy.Strict] — облако запрещено (пока нет локального провайдера) → [blocked]; Balanced/Permissive —
 *   работает, различаются вычисткой секретов из промпта ([SecretRedactor]).
 *
 * Независим от Vault: BYOK-настройки подаются лямбдой [settings] (как в [AiAssistantController]).
 */
class TerminalAiController(
    val policy: AiPolicy,
    private val settings: () -> AiSettings,
    private val providerFactory: (OpenAiConfig) -> AiProvider,
    private val scope: CoroutineScope,
) {
    private val decision = AiPolicyDecision.of(policy)

    /** Показывать ли бар для этого хоста вообще (false только для [AiPolicy.Off]). */
    val aiEnabled: Boolean get() = decision.aiEnabled

    /** Предложенная команда, ждущая подтверждения пользователя; `null` — предложения нет. */
    var pending by mutableStateOf<String?>(null); private set

    /**
     * Оценка риска [pending] ([CommandRiskClassifier]); `null` — нет предложения. UI показывает
     * предупреждение и для [app.skerry.shared.ai.CommandRisk.Danger] требует доп. подтверждения.
     */
    var pendingRisk by mutableStateOf<CommandAssessment?>(null); private set

    /** Частичный ответ во время генерации; `null` — генерации нет. */
    var streaming by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** Причина, по которой запрос не ушёл (политика/не настроено); `null` — не заблокировано. */
    var blocked by mutableStateOf<String?>(null); private set

    private var job: Job? = null

    /** Запросить команду. No-op, если занят/пусто/AI выключен. Секрет облака не уходит при Strict/не настроено. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !decision.aiEnabled) return
        error = null
        blocked = null
        pending = null
        pendingRisk = null
        val current = settings()
        if (!current.isConfigured) {
            blocked = NOT_CONFIGURED
            return
        }
        if (!decision.cloudAllowed) {
            blocked = STRICT_BLOCKED
            return
        }
        val outbound = if (decision.sanitizeSecrets) SecretRedactor.redact(text) else text
        busy = true
        streaming = ""
        val config = current.toOpenAiConfig()
        val messages = listOf(AiMessage(AiRole.SYSTEM, COMMAND_PROMPT), AiMessage(AiRole.USER, outbound))
        job = scope.launch {
            var provider: AiProvider? = null
            val sb = StringBuilder()
            try {
                provider = providerFactory(config)
                provider.chat(AiChatRequest(config.model, messages)).collect { delta ->
                    sb.append(delta.text)
                    streaming = sb.toString()
                }
                val command = sanitizeCommand(sb.toString())
                when {
                    command == null -> error = "The assistant returned no command."
                    // По контракту COMMAND_PROMPT непечатаемый/невозможный запрос модель возвращает
                    // строкой на «#» — это объяснение, а не команда: показываем как ошибку, не исполняем.
                    command.startsWith("#") ->
                        error = command.trimStart('#').trim().ifEmpty { "The assistant declined this request." }
                    else -> {
                        pending = command
                        pendingRisk = CommandRiskClassifier.assess(command)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                error = friendly(e)
            } catch (e: Exception) {
                error = "AI request failed: ${e.message}"
            } finally {
                provider?.let { runCatching { it.close() } }
                streaming = null
                busy = false
            }
        }
    }

    /**
     * Пользователь подтвердил (нажал Run). Возвращает команду и очищает [pending]. Вызывающий шлёт её
     * в терминал с CR (Enter) — это и есть подтверждение перед выполнением. Команда гарантированно одна
     * строка без управляющих байтов ([sanitizeCommand]), поэтому один CR исполняет ровно её, не цепочку.
     */
    fun confirm(): String? {
        val command = pending
        pending = null
        pendingRisk = null
        return command
    }

    /**
     * Привести сырой вывод модели к ОДНОЙ строке ввода без управляющих символов и markdown-обёрток.
     * Критично для инварианта «подтверждение перед выполнением»: вставляемая по [confirm] команда
     * физически не может нести перевод строки (иначе `send` авто-исполнил бы её), даже если модель
     * вернула многострочный текст или CR/LF-инъекцию.
     *
     * Шаги: снимаем ```-заборчик (с возможным языковым тегом), берём первую непустую строку, режем
     * control-байты (кроме таба), затем срезаем одиночные inline-бэктики вокруг команды — иначе bash
     * воспримет `` `free -h` `` как подстановку команды (выполнит и попробует запустить её вывод).
     * `null` — команды нет.
     */
    private fun sanitizeCommand(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
            text = text.substring(3, text.length - 3)
            val firstTok = text.substringBefore('\n').trim()
            // ```bash / ```sh — языковой тег на первой строке заборчика, отбрасываем.
            if (firstTok.isNotEmpty() && firstTok.none { it.isWhitespace() } &&
                firstTok.all { it.isLetterOrDigit() || it == '-' }
            ) {
                text = text.substringAfter('\n', "")
            }
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = firstLine.filter { it == '\t' || it.code >= 0x20 }.trim().trim('`').trim()
        return cleaned.ifEmpty { null }
    }

    /** Отклонить предложение/сбросить сообщения. */
    fun dismiss() {
        pending = null
        pendingRisk = null
        error = null
        blocked = null
    }

    /** Отменить активный запрос (если идёт). */
    fun cancel() {
        job?.cancel()
        busy = false
        streaming = null
    }

    private fun friendly(e: AiException): String = when (e.kind) {
        AiException.Kind.UNAUTHORIZED -> "Invalid API key — check it in AI settings."
        AiException.Kind.RATE_LIMITED -> "Rate limited by the provider. Try again shortly."
        AiException.Kind.NETWORK -> "Network error reaching the AI provider."
        AiException.Kind.INVALID_REQUEST -> "The provider rejected the request (check model/params)."
        AiException.Kind.PROTOCOL -> "Unexpected response from the AI provider."
    }

    companion object {
        const val NOT_CONFIGURED = "Add an API key in AI settings first."
        const val STRICT_BLOCKED = "Strict policy: cloud AI is off for this host."

        const val COMMAND_PROMPT =
            "You translate the user's request into a SINGLE shell command for a POSIX/Linux SSH session. " +
                "Output ONLY the command — no explanation, no markdown, no backticks. If the request is " +
                "unsafe or impossible, output a single line starting with '#' explaining why. Never invent " +
                "credentials or hostnames."
    }
}
