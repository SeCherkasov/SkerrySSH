package app.skerry.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiEndpoint
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiRoute
import app.skerry.shared.ai.AiRouter
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.CommandAssessment
import app.skerry.shared.ai.CommandRiskClassifier
import app.skerry.shared.ai.SecretRedactor
import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Контроллер терминального AI-бара: превращает запрос на естественном языке в ОДНУ shell-команду
 * под per-host политикой [AiPolicy] (принцип «AI under policy»).
 *
 * Инварианты безопасности:
 * - **Подтверждение перед выполнением — всегда.** [ask] лишь кладёт предложенную команду в [pending];
 *   исполнить (вставить в ввод терминала) её можно только через явный [confirm]. Автозапуска нет
 *   ни при какой политике — вывод модели (в т.ч. локальной) считается недоверенным.
 * - Политика + настройки выбирают эндпоинт через [AiRouter]: [AiPolicy.Off] — бар скрыт;
 *   [AiPolicy.Strict] — только локальная модель (без неё → [blocked]); Balanced/Permissive —
 *   провайдер из настроек, различаются вычисткой секретов из промпта ([SecretRedactor]).
 * - Разбор/санитизация ответа модели — в [AiReplyParser] (чистые функции, тестируются напрямую).
 *
 * Независим от Vault: настройки подаются лямбдой [settings] (как в [AiAssistantController]);
 * [localInstalled] — скачана ли локальная модель на этом устройстве.
 */
class TerminalAiController(
    val policy: AiPolicy,
    private val settings: () -> AiSettings,
    providerFactory: (AiEndpoint) -> AiProvider,
    scope: CoroutineScope,
    // Язык, на котором модель должна писать INFO/ASK (= язык интерфейса). Читается лениво при каждом
    // запросе, чтобы смена языка в настройках отражалась без пересоздания контроллера. Значение —
    // англоязычное имя языка для промпта («English»/«Russian»); дефолт English (мок/превью/тесты).
    private val responseLanguage: () -> String = { "English" },
    private val localInstalled: (LocalModel) -> Boolean = { false },
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

    /** Запросить команду. No-op, если занят/пусто/AI выключен. Ничего не уходит, пока маршрут не разрешён. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !decision.aiEnabled) return
        error = null
        blocked = null
        pending = null
        pendingRisk = null
        pendingInfo = null
        val current = settings()
        val device = LocalModelCatalog.resolve(current.localModelId)
        val route = AiRouter.route(decision, current, device, localInstalled(device))
        if (route !is AiRoute.Use) {
            blocked = blockedMessage((route as AiRoute.Blocked).reason)
            return
        }
        val outbound = if (decision.sanitizeSecrets) SecretRedactor.redact(text) else text
        busy = true
        streaming = ""
        val gen = ++generation
        val messages = listOf(AiMessage(AiRole.SYSTEM, commandPrompt(responseLanguage())), AiMessage(AiRole.USER, outbound))
        job = runner.launch(
            temperature = COMMAND_TEMPERATURE,
            endpoint = route.endpoint,
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

    /** Человекочитаемое объяснение, почему запрос не ушёл (маппинг причин [AiRouter]). */
    private fun blockedMessage(reason: AiRoute.Reason): String = when (reason) {
        AiRoute.Reason.CLOUD_NOT_CONFIGURED -> NOT_CONFIGURED
        AiRoute.Reason.DEVICE_NOT_READY -> DEVICE_NOT_READY
        AiRoute.Reason.STRICT_NEEDS_DEVICE -> STRICT_BLOCKED
        AiRoute.Reason.AI_DISABLED -> AI_DISABLED
    }

    companion object {
        /**
         * Температура генерации команды: почти детерминированная. На 0.7 маленькие локальные
         * модели превращают ответ в лотерею «CMD или ASK» — тот же вопрос то давал uptime,
         * то просил «уточнить метрику».
         */
        const val COMMAND_TEMPERATURE = 0.2

        const val NOT_CONFIGURED = "Add an API key in AI settings first."
        const val STRICT_BLOCKED = "Strict policy: download the on-device model in AI settings to use AI on this host."
        const val DEVICE_NOT_READY = "Download the on-device model in AI settings first."
        const val AI_DISABLED = "AI is turned off in AI settings."
        const val NEEDS_CLARIFICATION = "Please clarify your request."

        /**
         * Промпт превращения запроса в команду. [language] — англоязычное имя языка интерфейса
         * («English»/«Russian»), на котором модель должна писать человекочитаемые INFO/ASK, независимо
         * от языка запроса пользователя. Прокидывается из настроек через [responseLanguage].
         *
         * Формулировка рассчитана и на маленькие локальные модели (1–4B): им нужно ЯВНО сказать,
         * что команда выполнится на уже подключённом удалённом сервере (иначе «какая нагрузка на
         * сервере?» превращается в просьбу «дать информацию о сервере»), и показать few-shot-примеры —
         * без них мелкая модель уходит в ASK вместо очевидной команды.
         */
        fun commandPrompt(language: String): String =
            "You turn the user's request into ONE shell command for a POSIX/Linux system.\n" +
                "The command runs ON the remote server the user is ALREADY connected to over SSH. " +
                "Questions about the server — its load, memory, disks, processes, logs, uptime — are " +
                "answered by a command that prints that information. Never ask for details a command " +
                "could discover by itself.\n" +
                "Reply in ONE of two forms, nothing else:\n" +
                "1) First line `CMD: <command>` (only the command, no markdown, no backticks); " +
                "second line `INFO: <max 8-word description of what it does>`.\n" +
                "2) A single line `ASK: <short reason>` — ONLY if the request is truly ambiguous, " +
                "unsafe, or impossible.\n" +
                "If several commands could answer, choose the most common one — never ask which " +
                "tool, metric, or format to use.\n" +
                "Examples:\n" +
                "User: what is the load on the server?\n" +
                "CMD: uptime\n" +
                "INFO: shows uptime and load averages\n" +
                "User: how much free disk space is left?\n" +
                "CMD: df -h\n" +
                "INFO: shows disk usage per filesystem\n" +
                "Always write the INFO and ASK text in " + language + ", regardless of the language " +
                "the user asked in. Never invent credentials or hostnames."
    }
}
