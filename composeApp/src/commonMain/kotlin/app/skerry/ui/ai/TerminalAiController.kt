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
    // Язык, на котором модель должна писать INFO/ASK (= язык интерфейса). Читается лениво при каждом
    // запросе, чтобы смена языка в настройках отражалась без пересоздания контроллера. Значение —
    // англоязычное имя языка для промпта («English»/«Russian»); дефолт English (мок/превью/тесты).
    private val responseLanguage: () -> String = { "English" },
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
        job = scope.launch {
            var provider: AiProvider? = null
            val sb = StringBuilder()
            try {
                provider = providerFactory(config)
                provider.chat(AiChatRequest(config.model, messages)).collect { delta ->
                    sb.append(delta.text)
                    streaming = sb.toString()
                }
                applyReply(sb.toString())
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

    /**
     * Пользователь подтвердил (нажал Run). Возвращает команду и очищает [pending]. Вызывающий шлёт её
     * в терминал с CR (Enter) — это и есть подтверждение перед выполнением. Команда гарантированно одна
     * строка без управляющих байтов ([sanitizeCommand]), поэтому один CR исполняет ровно её, не цепочку.
     */
    fun confirm(): String? {
        val command = pending
        pending = null
        pendingRisk = null
        pendingInfo = null
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

    /**
     * Вторая непустая строка ответа модели — краткое пояснение, что делает команда (по [COMMAND_PROMPT]).
     * Снимаем маркеры списков/`#`/бэктики, режем до 120 символов. `null` — пояснения нет.
     */
    private fun extractDescription(raw: String): String? {
        val lines = raw.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val desc = lines.getOrNull(1) ?: return null
        val cleaned = desc.trimStart('#', '-', '*', '•', '>').trim().trim('`').trim()
            .filter { it == '\t' || it.code >= 0x20 }.trim()
        return cleaned.ifEmpty { null }?.take(120)
    }

    /**
     * Разобрать ответ модели ([COMMAND_PROMPT]): либо `CMD:`+`INFO:` (команда), либо `ASK:` (уточнение/
     * отказ — НЕ команда, показываем сообщением, не даём Run). Если маркеров нет — толерантный фолбэк:
     * первая строка как команда, но если она похожа на прозу (кириллица/вопрос/фраза-уточнение) — это
     * тоже сообщение, а не команда (иначе «уточните запрос…» попадало в слот с кнопкой Run).
     */
    private fun applyReply(raw: String) {
        val cmdLine = lineAfter(raw, "CMD:")
        val askLine = lineAfter(raw, "ASK:")
        val command = cmdLine?.let { sanitizeCommand(it) }
        when {
            command != null && !command.startsWith("#") && !looksLikeProse(command) -> {
                setPending(command, lineAfter(raw, "INFO:")?.let { cleanLine(it) })
            }
            askLine != null -> error = cleanLine(askLine) ?: NEEDS_CLARIFICATION
            else -> {
                val first = sanitizeCommand(raw)
                when {
                    first == null -> error = "The assistant returned no command."
                    first.startsWith("#") -> error = first.trimStart('#').trim().ifEmpty { NEEDS_CLARIFICATION }
                    looksLikeProse(first) -> error = first
                    else -> setPending(first, extractDescription(raw))
                }
            }
        }
    }

    private fun setPending(command: String, info: String?) {
        pending = command
        pendingRisk = CommandRiskClassifier.assess(command)
        pendingInfo = info
    }

    /** Первая строка, начинающаяся на [prefix] (регистронезависимо); возвращает остаток; `null` — нет. */
    private fun lineAfter(raw: String, prefix: String): String? {
        raw.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith(prefix, ignoreCase = true)) {
                return t.substring(prefix.length).trim().ifEmpty { null }
            }
        }
        return null
    }

    /** Вычистить служебную строку (INFO/ASK): бэктики, маркеры списков, control-байты; до 160 символов. */
    private fun cleanLine(s: String): String? {
        val c = s.trim().trim('`').trimStart('#', '-', '*', '•', '>').trim()
            .filter { it == '\t' || it.code >= 0x20 }.trim()
        return c.ifEmpty { null }?.take(160)
    }

    /**
     * Похоже ли на естественный язык (уточнение/отказ), а не на shell-команду. Эвристика-предохранитель
     * на случай, когда модель не проставила `ASK:`: кириллица, финальный «?» или типичные фразы-уточнения.
     */
    private fun looksLikeProse(s: String): Boolean {
        if (s.endsWith("?")) return true
        if (s.any { it in 'Ѐ'..'ӿ' }) return true
        val lower = s.lowercase()
        return PROSE_STARTERS.any { lower.startsWith(it) }
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
        const val NEEDS_CLARIFICATION = "Please clarify your request."

        private val PROSE_STARTERS = listOf(
            "please", "sorry", "could you", "can you", "which ", "what ", "i cannot", "i can't",
            "i'm ", "i am ", "unable", "clarify", "specify", "you need", "the request", "to run this",
        )

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
