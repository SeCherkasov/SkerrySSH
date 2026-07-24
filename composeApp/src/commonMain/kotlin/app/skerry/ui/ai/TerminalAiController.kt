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
 *   [AiPolicy.Strict] uses the local model only (absent it → [AiNotice.Blocked]); Balanced/Permissive use
 *   the configured provider, differing by prompt secret redaction ([SecretRedactor]).
 * - Model reply parsing/sanitization is in [AiReplyParser].
 *
 * Independent of Vault: settings supplied via [settings] lambda; [localInstalled] reports whether
 * the local model is downloaded on this device.
 */
/** Non-command outcome shown in the AI bar; at most one at a time (see [TerminalAiController.notice]). */
sealed interface AiNotice {
    /** The request was not sent (policy/not configured); typed so the UI localizes it (`aiBlockedMessage`). */
    data class Blocked(val reason: AiRoute.Reason) : AiNotice

    /** The model asked for clarification; [question] is shown so the user knows what to add. */
    data class Ask(val question: String) : AiNotice

    /** The reply was prose or nothing usable; the UI shows a fixed localized "not a command" message. */
    data object Rejected : AiNotice

    /** Provider/transport failure; the UI resolves the localized text (see `aiFailureMessage`). */
    data class Error(val failure: AiFailure) : AiNotice
}

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

    /**
     * Free-prose explanation of terminal output (from [explain]); the partial reply while streaming,
     * the final text on completion, and `null` when not explaining. Distinct from [pending], which is
     * a command — an explanation is only ever displayed, never offered for execution.
     */
    var explanation by mutableStateOf<String?>(null); private set

    /**
     * The non-command outcome shown in the bar, at most one at a time; cleared by [dismiss] and by
     * the next [ask]. A sealed type instead of parallel flags, so a new kind of notice cannot be
     * left showing alongside another.
     */
    var notice by mutableStateOf<AiNotice?>(null); private set

    private var job: Job? = null
    // Generation of the active request. cancel()/a new ask() increments it; the finally block resets
    // busy/streaming only if its generation is still current, so a late-finishing cancelled request
    // can't clobber the state of the next one.
    private var generation = 0

    /** Request a command. No-op if busy, empty, or AI is disabled. Nothing is sent until the route is resolved. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !decision.aiEnabled) return
        notice = null
        pending = null
        pendingRisk = null
        pendingInfo = null
        explanation = null
        val current = settings()
        val device = LocalModelCatalog.resolve(current.localModelId)
        val route = AiRouter.route(decision, current, device, localInstalled(device))
        if (route !is AiRoute.Use) {
            notice = AiNotice.Blocked((route as AiRoute.Blocked).reason)
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
            onError = { notice = AiNotice.Error(it) },
            onFinally = {
                if (gen == generation) {
                    streaming = null
                    busy = false
                }
            },
        )
    }

    /**
     * Ask the model to explain a chunk of terminal [output] — the current selection, or the visible
     * screen. No-op if busy, empty, or AI is disabled.
     *
     * Routing is identical to [ask], so the per-host policy is honoured: under [AiPolicy.Strict] the
     * output stays on the on-device model and is never sent to the cloud — terminal output can carry
     * secrets, so this must not be weaker than command generation. Balanced still redacts secrets
     * ([SecretRedactor]) before sending. Unlike [ask], the reply is free prose surfaced in
     * [explanation]; it is never parsed as a command or offered for execution.
     */
    fun explain(output: String) {
        val context = clampContext(output)
        if (busy || context.isEmpty() || !decision.aiEnabled) return
        notice = null
        pending = null
        pendingRisk = null
        pendingInfo = null
        explanation = null
        val current = settings()
        val device = LocalModelCatalog.resolve(current.localModelId)
        val route = AiRouter.route(decision, current, device, localInstalled(device))
        if (route !is AiRoute.Use) {
            notice = AiNotice.Blocked((route as AiRoute.Blocked).reason)
            return
        }
        val outbound = if (decision.sanitizeSecrets) SecretRedactor.redact(context) else context
        busy = true
        // Non-null (empty) marks the explain surface active so the bar shows it immediately, before
        // the first delta, instead of the generic "Thinking…" line.
        explanation = ""
        val gen = ++generation
        val messages = listOf(AiMessage(AiRole.SYSTEM, explainPrompt(responseLanguage())), AiMessage(AiRole.USER, outbound))
        job = runner.launch(
            temperature = EXPLAIN_TEMPERATURE,
            endpoint = route.endpoint,
            messages = messages,
            onDelta = { explanation = it },
            onComplete = { reply ->
                val trimmed = reply.trim()
                // A model that returns nothing usable falls back to the same fixed notice as a command
                // reply with no content, rather than leaving an empty panel open.
                if (trimmed.isEmpty()) {
                    explanation = null
                    notice = AiNotice.Rejected
                } else {
                    explanation = trimmed
                }
            },
            onError = {
                explanation = null
                notice = AiNotice.Error(it)
            },
            onFinally = {
                if (gen == generation) busy = false
            },
        )
    }

    /** Keep the tail of long output within [EXPLAIN_CONTEXT_LIMIT]; the most recent lines matter most. */
    private fun clampContext(output: String): String {
        val trimmed = output.trim()
        if (trimmed.length <= EXPLAIN_CONTEXT_LIMIT) return trimmed
        return "…" + trimmed.substring(trimmed.length - EXPLAIN_CONTEXT_LIMIT)
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
     * Dispatch a parsed [AiReplyParser.parse] reply. Only a command is surfaced as [pending]. An
     * ASK is the protocol-level clarification the system prompt itself requests for ambiguous
     * tasks: its question is shown ([AiNotice.Ask]), or the user could never learn what to add.
     * Prose and empty replies stay a fixed "not a command" notice — small local models routinely
     * answer greetings with off-topic prose, and echoing that back is noise.
     */
    private fun applyReply(raw: String) {
        when (val reply = AiReplyParser.parse(raw)) {
            is AiReplyParser.Reply.Command -> setPending(reply.command, reply.info)
            is AiReplyParser.Reply.Ask ->
                notice = reply.text?.let { AiNotice.Ask(it) } ?: AiNotice.Rejected
            is AiReplyParser.Reply.Prose, AiReplyParser.Reply.NoCommand -> notice = AiNotice.Rejected
        }
    }

    private fun setPending(command: String, info: String?) {
        pending = command
        pendingRisk = CommandRiskClassifier.assess(command)
        pendingInfo = info
    }

    /** Dismiss the proposal, explanation, or notice; cancels an in-flight explanation. */
    fun dismiss() {
        if (busy) cancel()
        pending = null
        pendingRisk = null
        pendingInfo = null
        explanation = null
        notice = null
    }

    /** Cancel the active request, if any. */
    fun cancel() {
        generation++
        job?.cancel()
        busy = false
        streaming = null
        explanation = null
    }

    companion object {
        /** Command-generation temperature: near-deterministic; small local models are unreliable at higher values. */
        const val COMMAND_TEMPERATURE = 0.2

        /** Explanation temperature: a touch higher than commands for readable prose, still low for small local models. */
        const val EXPLAIN_TEMPERATURE = 0.3

        /**
         * Max characters of terminal output sent for an explanation. The tail is kept (recent output
         * is what the user is asking about), bounding request size for both cloud and small local models.
         */
        const val EXPLAIN_CONTEXT_LIMIT = 6000

        /**
         * Prompt that turns a chunk of terminal output into a plain-language explanation. [language]
         * is the English name of the UI language the explanation must be written in (see [ask]). Plain
         * prose only — the AI bar renders text, not markdown — and grounded strictly in the given output.
         */
        fun explainPrompt(language: String): String =
            // The language mandate is front-loaded AND repeated at the end: the command output is
            // usually English, and a single trailing instruction was ignored — the model mirrored the
            // output's language instead of the UI's.
            "Write your entire reply in " + language + ". This is mandatory: even though the command " +
                "output below is in another language, the explanation must be in " + language + ".\n" +
                "You explain terminal output for a user connected to a remote server over SSH. " +
                "The user's message is raw output from a command that ran on that server. Explain " +
                "concisely what it means: what happened, what any errors or warnings indicate, and — if " +
                "something failed — the likely cause and a safe next step. Base the explanation only on " +
                "the given output; never invent files, hosts, or values that are not shown.\n" +
                "Write plain prose (short paragraphs or a short bullet list), no markdown headings and no " +
                "code fences. Remember: the entire explanation must be written in " + language + "."

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
