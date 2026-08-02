package app.skerry.ui.ai

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
