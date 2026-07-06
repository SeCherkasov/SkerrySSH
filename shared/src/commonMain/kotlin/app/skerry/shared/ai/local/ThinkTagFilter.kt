package app.skerry.shared.ai.local

/**
 * Strips a leading `<think>…</think>` reasoning block from a local model's streamed output
 * (Qwen3 emits it even with `/no_think`, empty). Operates on deltas: the tag may arrive split
 * across chunks. Everything after the closing tag (minus leading newlines) passes through as-is;
 * a `<think>` mid-response is treated as content and left alone.
 *
 * One instance per generation; state is not reused across streams.
 */
class ThinkTagFilter {

    private enum class State { LEADING, INSIDE, AFTER, PASS }

    private var state = State.LEADING
    private val buffer = StringBuilder()

    /** Feeds the next delta through the filter; returns the text to emit (may be empty). */
    fun feed(text: String): String {
        if (state == State.PASS) return text
        buffer.append(text)
        return drain()
    }

    /** Tail at stream end: buffer contents that never turned out to be a `<think>` block. */
    fun tail(): String = when (state) {
        State.LEADING -> buffer.toString()
        else -> ""
    }

    private fun drain(): String {
        if (state == State.LEADING) {
            val lead = buffer.toString().trimStart()
            when {
                lead.startsWith(OPEN) -> {
                    state = State.INSIDE
                    val inside = lead.removePrefix(OPEN)
                    buffer.clear()
                    buffer.append(inside)
                }
                // May still turn into the start of "<think>" (leading whitespace) — keep buffering.
                OPEN.startsWith(lead) -> return ""
                else -> {
                    state = State.PASS
                    val out = buffer.toString()
                    buffer.clear()
                    return out
                }
            }
        }
        if (state == State.INSIDE) {
            val closeAt = buffer.indexOf(CLOSE)
            if (closeAt < 0) {
                // Keep only the tail that could start "</think>" — don't accumulate reasoning text.
                val keep = buffer.takeLast(CLOSE.length - 1).toString()
                buffer.clear()
                buffer.append(keep)
                return ""
            }
            val rest = buffer.substring(closeAt + CLOSE.length)
            buffer.clear()
            state = State.AFTER
            buffer.append(rest)
        }
        // AFTER: trim newlines right after </think>, then pure passthrough.
        val out = buffer.toString().trimStart()
        return if (out.isEmpty()) {
            buffer.clear()
            ""
        } else {
            state = State.PASS
            buffer.clear()
            out
        }
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
