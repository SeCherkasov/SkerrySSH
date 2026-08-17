package app.skerry.ui.ai

import app.skerry.shared.ai.AiMessage

/**
 * Max characters of terminal output sent to a model as context. Bounds the request for both cloud
 * and small local models; shared by the session assistant and the one-shot "explain this output".
 */
internal const val AI_CONTEXT_LIMIT = 6000

/**
 * Keep the tail of long terminal [output] within [AI_CONTEXT_LIMIT] — the most recent lines are what
 * the user is asking about. A truncated context is marked with a leading ellipsis so the model (and
 * a reader of the request) can tell it isn't the whole screen.
 */
internal fun clampAiContext(output: String): String {
    val trimmed = output.trim()
    if (trimmed.length <= AI_CONTEXT_LIMIT) return trimmed
    return "…" + trimmed.substring(trimmed.length - AI_CONTEXT_LIMIT)
}

/**
 * Max characters of conversation replayed with a question. Every turn goes back to the model on
 * every request, so without a bound a long conversation walks past what the endpoint accepts: a
 * local model (4096 tokens on desktop, 2048 on a phone) drops the front of the prompt itself and
 * the answers quietly get worse, while a cloud endpoint starts answering 400.
 */
internal const val AI_HISTORY_LIMIT = 12_000

/**
 * The tail of [history] that fits in [AI_HISTORY_LIMIT] characters — the oldest turns are dropped
 * first, as the least relevant to the question being asked. The newest message is always kept, even
 * when it alone exceeds the budget: a request without it has nothing to answer.
 */
internal fun clampAiHistory(history: List<AiMessage>): List<AiMessage> {
    var budget = AI_HISTORY_LIMIT
    val kept = ArrayDeque<AiMessage>()
    for (message in history.asReversed()) {
        budget -= message.content.length
        if (budget < 0 && kept.isNotEmpty()) break
        kept.addFirst(message)
    }
    return kept
}
