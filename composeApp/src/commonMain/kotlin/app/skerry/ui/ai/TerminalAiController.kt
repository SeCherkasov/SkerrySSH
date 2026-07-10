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
 * Terminal AI-bar controller: turns a natural-language request into ONE shell command under the
 * per-host [AiPolicy].
 *
 * Safety invariants:
 * - Confirmation before execution: [ask] only stores the proposed command in [pending]; it runs
 *   (is inserted into terminal input) only via explicit [confirm]. No auto-run under any policy;
 *   model output (including local) is untrusted.
 * - Policy + settings select the endpoint via [AiRouter]: [AiPolicy.Off] hides the bar;
 *   [AiPolicy.Strict] uses the local model only (absent it → [blocked]); Balanced/Permissive use
 *   the configured provider, differing by prompt secret redaction ([SecretRedactor]).
 * - Model reply parsing/sanitization is in [AiReplyParser].
 *
 * Independent of Vault: settings supplied via [settings] lambda; [localInstalled] reports whether
 * the local model is downloaded on this device.
 */
class TerminalAiController(
    val policy: AiPolicy,
    private val settings: () -> AiSettings,
    providerFactory: (AiEndpoint) -> AiProvider,
    scope: CoroutineScope,
    // Language for the model's INFO/ASK text (= UI language). Read lazily per request so a settings
    // change applies without recreating the controller. English name of the language (e.g. "English",
    // "Russian"); defaults to English.
    private val responseLanguage: () -> String = { "English" },
    private val localInstalled: (LocalModel) -> Boolean = { false },
) {
    private val decision = AiPolicyDecision.of(policy)
    private val runner = AiStreamRunner(providerFactory, scope)

    /** Whether the bar is shown for this host at all (false only for [AiPolicy.Off]). */
    val aiEnabled: Boolean get() = decision.aiEnabled

    /** Proposed command awaiting user confirmation; `null` if none. */
    var pending by mutableStateOf<String?>(null); private set

    /**
     * Risk assessment of [pending] ([CommandRiskClassifier]); `null` if none. The UI warns and, for
     * [app.skerry.shared.ai.CommandRisk.Danger], requires extra confirmation.
     */
    var pendingRisk by mutableStateOf<CommandAssessment?>(null); private set

    /** Short description of what [pending] does (the model's second reply line); `null` if none. */
    var pendingInfo by mutableStateOf<String?>(null); private set

    /** Partial reply while streaming; `null` when not generating. */
    var streaming by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /**
     * The reply was not a runnable command (a clarification, prose, or nothing usable). The bar shows
     * one fixed, localized "not a command" message — the model's own text is untrusted and often
     * off-topic, so it is never surfaced. The UI supplies the localized string.
     */
    var rejected by mutableStateOf(false); private set

    /**
     * Why the request was not sent (policy/not configured); `null` if not blocked. Typed so the UI
     * localizes it (see `aiBlockedMessage`) instead of the controller hardcoding an English string.
     */
    var blocked by mutableStateOf<AiRoute.Reason?>(null); private set

    private var job: Job? = null
    // Generation of the active request. cancel()/a new ask() increments it; the finally block resets
    // busy/streaming only if its generation is still current, so a late-finishing cancelled request
    // can't clobber the state of the next one.
    private var generation = 0

    /** Request a command. No-op if busy, empty, or AI is disabled. Nothing is sent until the route is resolved. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !decision.aiEnabled) return
        error = null
        blocked = null
        rejected = false
        pending = null
        pendingRisk = null
        pendingInfo = null
        val current = settings()
        val device = LocalModelCatalog.resolve(current.localModelId)
        val route = AiRouter.route(decision, current, device, localInstalled(device))
        if (route !is AiRoute.Use) {
            blocked = (route as AiRoute.Blocked).reason
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
     * User confirmed (pressed Run). Returns the command and clears [pending]; the caller sends it to
     * the terminal followed by CR. The command is guaranteed single-line with no control bytes
     * ([AiReplyParser.sanitizeCommand]), so one CR executes exactly it, not a chain.
     */
    fun confirm(): String? {
        val command = pending
        pending = null
        pendingRisk = null
        pendingInfo = null
        return command
    }

    /**
     * Dispatch a parsed [AiReplyParser.parse] reply. Only a command is surfaced; anything else — a
     * clarification, prose, or nothing usable — is a single [rejected] state. We deliberately drop
     * the model's own text: small local models routinely answer greetings and small talk with
     * off-topic prose, so a fixed "not a command" message is clearer than echoing that back.
     */
    private fun applyReply(raw: String) {
        when (val reply = AiReplyParser.parse(raw)) {
            is AiReplyParser.Reply.Command -> setPending(reply.command, reply.info)
            is AiReplyParser.Reply.Ask, is AiReplyParser.Reply.Prose, AiReplyParser.Reply.NoCommand ->
                rejected = true
        }
    }

    private fun setPending(command: String, info: String?) {
        pending = command
        pendingRisk = CommandRiskClassifier.assess(command)
        pendingInfo = info
    }

    /** Dismiss the proposal and clear messages. */
    fun dismiss() {
        pending = null
        pendingRisk = null
        pendingInfo = null
        error = null
        blocked = null
        rejected = false
    }

    /** Cancel the active request, if any. */
    fun cancel() {
        generation++
        job?.cancel()
        busy = false
        streaming = null
    }

    companion object {
        /** Command-generation temperature: near-deterministic; small local models are unreliable at higher values. */
        const val COMMAND_TEMPERATURE = 0.2

        /**
         * Prompt that turns a request into a command. [language] is the English name of the UI
         * language in which the model must write INFO/ASK text, independent of the user's request
         * language; supplied from settings via [responseLanguage]. States explicitly that the command
         * runs on the already-connected remote server and includes few-shot examples, since small
         * local models otherwise default to asking for clarification.
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
