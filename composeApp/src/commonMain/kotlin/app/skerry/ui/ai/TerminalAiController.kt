package app.skerry.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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
 * - Разбор/санитизация ответа модели — в [AiReplyParser] (чистые функции, тестируются напрямую).
 *
 * Независим от Vault: BYOK-настройки подаются лямбдой [settings] (как в [AiAssistantController]).
 */
class TerminalAiController(
    val policy: AiPolicy,
    private val settings: () -> AiSettings,
    providerFactory: (OpenAiConfig) -> AiProvider,
    scope: CoroutineScope,
    // Язык, на котором модель должна писать INFO/ASK (= язык интерфейса). Читается лениво при каждом
    // запросе, чтобы смена языка в настройках отражалась без пересоздания контроллера. Значение —
    // англоязычное имя языка для промпта («English»/«Russian»); дефолт English (мок/превью/тесты).
    private val responseLanguage: () -> String = { "English" },
) {
    private val decision = AiPolicyDecision.of(policy)
    private val runner = AiStreamRunner(providerFactory, scope)

    /** Показывать ли бар для этого хоста вообще (false только для [AiPolicy.Off]). */
    val aiEnabled: Boolean get() = decision.aiEnabled

    /** Предложенная команда, ждущая подтверждения пользователя; `null` — предложения нет. */
    var pending by mutableStateOf<String?>(null); private set

    /**
     * Оценка риска [pending] ([CommandRiskClassifier]); `null` — нет предложения. UI показывает
     * предупреждение и для [app.skerry.shared.ai.CommandRisk.Danger] требует доп. подтверждения.
     */
    var pendingRisk by mutableStateOf<CommandAssessment?>(null); private set

    /** Краткое пояснение, что делает [pending] (вторая строка ответа модели); `null` — нет. */
    var pendingInfo by mutableStateOf<String?>(null); private set

    /** Частичный ответ во время генерации; `null` — генерации нет. */
    var streaming by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** Причина, по которой запрос не ушёл (политика/не настроено); `null` — не заблокировано. */
    var blocked by mutableStateOf<String?>(null); private set

    private var job: Job? = null
    // Поколение активного запроса. cancel()/новый ask() увеличивают его; finally сбрасывает busy/streaming
    // только если его поколение всё ещё текущее — иначе поздно завершившийся отменённый запрос затирал бы
    // состояние уже запущенного следующего (job-reassignment race).
    private var generation = 0

    /** Запросить команду. No-op, если занят/пусто/AI выключен. Секрет облака не уходит при Strict/не настроено. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !decision.aiEnabled) return
        error = null
        blocked = null
        pending = null
        pendingRisk = null
        pendingInfo = null
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
        val gen = ++generation
        val config = current.toOpenAiConfig()
        val messages = listOf(AiMessage(AiRole.SYSTEM, commandPrompt(responseLanguage())), AiMessage(AiRole.USER, outbound))
        job = runner.launch(
            config = config,
            messages = messages,
            onDelta = { streaming = it },
            onComplete = { applyReply(it) },
            onError = { error = it },
            onFinally = {
                if (gen == generation) {
                    streaming = null
                    busy = false
                }
            },
        )
    }

    /**
     * Пользователь подтвердил (нажал Run). Возвращает команду и очищает [pending]. Вызывающий шлёт её
     * в терминал с CR (Enter) — это и есть подтверждение перед выполнением. Команда гарантированно одна
     * строка без управляющих байтов ([AiReplyParser.sanitizeCommand]), поэтому один CR исполняет ровно
     * её, не цепочку.
     */
    fun confirm(): String? {
        val command = pending
        pending = null
        pendingRisk = null
        pendingInfo = null
        return command
    }

    /** Разложить разобранный [AiReplyParser.parse]-ответ по слотам состояния бара. */
    private fun applyReply(raw: String) {
        when (val reply = AiReplyParser.parse(raw)) {
            is AiReplyParser.Reply.Command -> setPending(reply.command, reply.info)
            is AiReplyParser.Reply.Ask -> error = reply.text ?: NEEDS_CLARIFICATION
            is AiReplyParser.Reply.Prose -> error = reply.text
            AiReplyParser.Reply.NoCommand -> error = "The assistant returned no command."
        }
    }

    private fun setPending(command: String, info: String?) {
        pending = command
        pendingRisk = CommandRiskClassifier.assess(command)
        pendingInfo = info
    }

    /** Отклонить предложение/сбросить сообщения. */
    fun dismiss() {
        pending = null
        pendingRisk = null
        pendingInfo = null
        error = null
        blocked = null
    }

    /** Отменить активный запрос (если идёт). */
    fun cancel() {
        generation++
        job?.cancel()
        busy = false
        streaming = null
    }

    companion object {
        const val NOT_CONFIGURED = "Add an API key in AI settings first."
        const val STRICT_BLOCKED = "Strict policy: cloud AI is off for this host."
        const val NEEDS_CLARIFICATION = "Please clarify your request."

        /**
         * Промпт превращения запроса в команду. [language] — англоязычное имя языка интерфейса
         * («English»/«Russian»), на котором модель должна писать человекочитаемые INFO/ASK, независимо
         * от языка запроса пользователя. Прокидывается из настроек через [responseLanguage].
         */
        fun commandPrompt(language: String): String =
            "You turn the user's request into a shell command for a POSIX/Linux SSH session. " +
                "Reply in ONE of two forms, nothing else:\n" +
                "1) If you can produce a command — first line `CMD: <command>` (only the command, no markdown, " +
                "no backticks); second line `INFO: <max 8-word description of what it does>`.\n" +
                "2) If the request is unclear, unsafe, or impossible — a single line `ASK: <short clarification or reason>`.\n" +
                "Always write the INFO and ASK text in " + language + ", regardless of the language the user asked in. " +
                "Never invent credentials or hostnames."
    }
}
