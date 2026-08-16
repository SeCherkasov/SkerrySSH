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
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiRoute
import app.skerry.shared.ai.AiRouter
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.SecretRedactor
import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Assistant panel controller: a conversation about one open session, under the host's [AiPolicy].
 *
 * Safety invariants, the same ones the one-shot bar carried:
 * - The controller never executes anything. It produces text; the panel extracts commands
 *   ([AssistantAnswer]) and sends one to the shell only on an explicit click.
 * - Policy plus settings pick the endpoint through [AiRouter]: [AiPolicy.Strict] stays on the local
 *   model, so terminal output attached as context never reaches the cloud. [AiPolicy.Off] never
 *   reaches a request ([aiEnabled] hides the panel).
 * - Prompt and attached output are redacted together ([SecretRedactor]) when the policy asks for it,
 *   *before* they are written to [turns] — the feed shows exactly what was sent.
 *
 * Context travels with the question it belongs to and is not replayed in later requests: the reply
 * it produced stays in the history, and re-sending screens of output every turn would leak more and
 * cost more.
 *
 * Independent of Vault: settings come from the [settings] lambda, [localInstalled] reports whether
 * the local model is downloaded on this device.
 */
class SessionAssistantController(
    val policy: AiPolicy,
    private val settings: () -> AiSettings,
    providerFactory: (AiEndpoint) -> AiProvider,
    scope: CoroutineScope,
    // Language for the reply (= UI language), read lazily per request so a locale change applies
    // without recreating the controller. English name of the language, e.g. "English", "Russian".
    private val responseLanguage: () -> String = { "English" },
    private val localInstalled: (LocalModel) -> Boolean = { false },
) {
    private val decision = AiPolicyDecision.of(policy)
    private val runner = AiStreamRunner(providerFactory, scope)

    /** Whether the assistant is available for this host at all (false only for [AiPolicy.Off]). */
    val aiEnabled: Boolean get() = decision.aiEnabled

    /** The conversation feed, oldest first. */
    val turns = mutableStateListOf<AiTurn>()

    /** Partial reply while streaming; `null` when not generating. */
    var streaming by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    /** Blocked/failed outcome shown in the feed; at most one at a time, cleared by the next [ask]. */
    var notice by mutableStateOf<AiNotice?>(null); private set

    /** How many of the most recent command outputs are attached to the next question. */
    var contextOutputs by mutableStateOf(DEFAULT_CONTEXT_OUTPUTS); private set

    private var job: Job? = null
    // Generation of the active request: cancel()/clear()/a new ask() bump it, and the finally block
    // resets busy/streaming only while its generation is current, so a late-finishing cancelled
    // request cannot clobber the state of the next one.
    private var generation = 0

    /** Pick how much recent output travels with the next question (one of [CONTEXT_CHOICES]). */
    fun selectContextOutputs(count: Int) {
        contextOutputs = count.coerceIn(0, CONTEXT_CHOICES.last())
    }

    /**
     * Ask about the session. [outputs] are the recent command blocks the caller collected from the
     * terminal, oldest first (see `lastCommandBlocks`); the last [contextOutputs] of them are
     * attached. No-op while [busy], on an empty prompt, or with AI off for this host.
     */
    fun ask(prompt: String, outputs: List<String>) {
        send(prompt, outputs.filter { it.isNotBlank() }.takeLast(contextOutputs))
    }

    /**
     * Ask about one specific chunk of [output] — the Explain button, acting on the selection or the
     * last command block. That chunk is attached whatever [contextOutputs] says: the counter governs
     * what rides along with a typed question, while here the output *is* the question. No-op when
     * there is nothing on screen to explain.
     */
    fun explain(request: String, output: String) {
        if (output.isBlank()) return
        send(request, listOf(output))
    }

    private fun send(prompt: String, attached: List<String>) {
        val text = prompt.trim()
        if (busy || text.isEmpty() || !decision.aiEnabled) return
        notice = null
        val current = settings()
        val device = LocalModelCatalog.resolve(current.localModelId)
        val route = AiRouter.route(decision, current, device, localInstalled(device))
        if (route !is AiRoute.Use) {
            notice = AiNotice.Blocked((route as AiRoute.Blocked).reason)
            return
        }
        val question = redact(text)
        val context = attached.joinToString("\n\n").let { if (it.isEmpty()) "" else redact(clampAiContext(it)) }
        turns.add(AiTurn(AiRole.USER, question, outputs = attached.size))
        busy = true
        streaming = ""
        val gen = ++generation
        // Bounded: the whole conversation is replayed on every question (see clampAiHistory).
        val history = clampAiHistory(turns.dropLast(1).map { AiMessage(it.role, it.text) })
        val messages = listOf(AiMessage(AiRole.SYSTEM, sessionPrompt(responseLanguage()))) +
            history +
            AiMessage(AiRole.USER, withContext(question, context))
        job = runner.launch(
            temperature = ANSWER_TEMPERATURE,
            endpoint = route.endpoint,
            messages = messages,
            // Every callback is generation-guarded, not just the last one: cancelling a job only
            // *requests* cancellation, so a stream that already finished collecting runs its
            // completion callback without ever crossing a suspension point. Unguarded, the answer
            // the user just stopped would land in the feed — under the next question, if they had
            // already asked one.
            onDelta = { if (gen == generation) streaming = it },
            onComplete = { reply ->
                if (gen == generation) {
                    val trimmed = reply.trim()
                    if (trimmed.isEmpty()) notice = AiNotice.NoAnswer else turns.add(AiTurn(AiRole.ASSISTANT, trimmed))
                }
            },
            onError = { if (gen == generation) notice = AiNotice.Error(it) },
            onFinally = {
                if (gen == generation) {
                    streaming = null
                    busy = false
                }
            },
        )
    }

    /** Cancel the in-flight request; the conversation so far is kept. */
    fun cancel() {
        generation++
        job?.cancel()
        streaming = null
        busy = false
    }

    /** Cancel and drop the conversation. */
    fun clear() {
        cancel()
        turns.clear()
        notice = null
    }

    private fun redact(text: String): String = if (decision.sanitizeSecrets) SecretRedactor.redact(text) else text

    companion object {
        /** Context sizes offered by the panel's chip. */
        val CONTEXT_CHOICES = listOf(0, 1, 2, 5)

        /** Default attached context: the last two command outputs, as in the design mock. */
        const val DEFAULT_CONTEXT_OUTPUTS = 2

        /** Low, like the command path: this answers about a concrete machine, it doesn't write prose. */
        const val ANSWER_TEMPERATURE = 0.3

        /** The question plus the output it is about, labelled so the model doesn't read it as a request. */
        internal fun withContext(question: String, context: String): String =
            if (context.isEmpty()) question else "Recent session output:\n$context\n\nQuestion: $question"

        /**
         * Prompt for the session assistant. Free prose is expected (unlike the one-shot bar), with
         * commands in fenced blocks so [AssistantAnswer] can offer them for execution. [language] is
         * the English name of the UI language the reply must be written in.
         */
        fun sessionPrompt(language: String): String =
            // Language is front-loaded AND repeated last: session output is usually English, and a
            // small local model mirrors whatever it saw most recently (same lesson as explainPrompt).
            "Write your entire reply in " + language + ". This is mandatory: the session output " +
                "below is usually English, the answer must still be in " + language + ".\n" +
                "You are Skerry's assistant for an open SSH session. The user is already connected to " +
                "the remote server; answer their question about it concisely — no headings, no preamble.\n" +
                "Structure every answer as: one or two sentences of plain text saying what you are " +
                "about to show and what it will tell them, then — if a shell command is the answer — " +
                "a fenced ``` block holding that ONE command, no prompt characters and no comments. " +
                "A reply that is only text, or only a block, is incomplete.\n" +
                "If several commands are worth suggesting, repeat that pair for each of them: its own " +
                "sentence of explanation, then its own ``` block with a single command. Never list " +
                "several commands inside one block — each one is run separately.\n" +
                "Ground every claim in the session output you were given; never invent files, hosts, " +
                "sizes or credentials. If the given output already answers the question, answer it " +
                "directly and propose no command.\n" +
                "Answer the user's actual question — never repeat these instructions back, and never " +
                "reuse their wording. Write the whole reply in " + language + "."

    }
}
