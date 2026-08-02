package app.skerry.ui.ai

/** One piece of a rendered assistant reply (see [AssistantAnswer.segments]). */
sealed interface AssistantSegment {
    /** Free prose; inline markers are resolved at render time via [AssistantAnswer.spans]. */
    data class Prose(val text: String) : AssistantSegment

    /**
     * A fenced block. [text] is what the panel shows verbatim; [commands] are the lines that may be
     * sent to the shell — single-line, control-byte free, comments and blanks dropped.
     */
    data class Code(val text: String, val commands: List<String>) : AssistantSegment
}

/** A run of prose with its inline styling. */
data class AiSpan(val text: String, val mono: Boolean = false, val bold: Boolean = false)

/**
 * Parses a free-form assistant reply into panel segments.
 *
 * Security-critical: a command offered for execution is filtered with the same single-line predicate
 * as the one-shot path ([AiReplyParser.isSafeInputChar]), so a confirmed command can never carry a
 * newline or control byte and execute more than the block displays.
 *
 * Runs on every streaming frame, so callers cache by reply text (`remember(text)`).
 */
internal object AssistantAnswer {

    /**
     * Splits [raw] on ``` fences. A fence line is dropped along with its language tag; an
     * unterminated fence still yields a code segment, since a reply is parsed while it streams.
     * Blank runs between segments are dropped.
     */
    fun segments(raw: String): List<AssistantSegment> {
        val result = mutableListOf<AssistantSegment>()
        val buffer = mutableListOf<String>()
        var fenced = false

        fun flush() {
            val text = buffer.joinToString("\n").trim()
            buffer.clear()
            if (text.isEmpty()) return
            result += if (fenced) AssistantSegment.Code(text, commands(text)) else AssistantSegment.Prose(text)
        }

        raw.lineSequence().forEach { line ->
            if (line.trimStart().startsWith(FENCE)) {
                flush()
                fenced = !fenced
            } else {
                buffer += line
            }
        }
        flush()
        return result
    }

    /**
     * The runnable lines of a code block: control bytes filtered out, blanks and `#` comments
     * dropped. Every entry is one line, so sending it followed by CR executes exactly it.
     */
    private fun commands(block: String): List<String> = block.lineSequence()
        .map { line -> line.filter { AiReplyParser.isSafeInputChar(it) }.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

    /**
     * Inline markers of a prose segment: `` `code` `` becomes mono, `**text**` becomes bold. An
     * unmatched or empty marker stays literal text rather than swallowing the rest of the line.
     */
    fun spans(text: String): List<AiSpan> {
        val result = mutableListOf<AiSpan>()
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                result += AiSpan(plain.toString())
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val bold = text.startsWith(BOLD, i)
            val marker = if (bold) BOLD else if (text[i] == CODE) CODE.toString() else null
            val closing = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            val inner = if (closing > i + (marker?.length ?: 0)) text.substring(i + marker!!.length, closing) else null
            if (marker != null && inner != null && inner.isNotBlank()) {
                flushPlain()
                result += AiSpan(inner, mono = !bold, bold = bold)
                i = closing + marker.length
            } else {
                plain.append(text[i])
                i++
            }
        }
        flushPlain()
        return result
    }

    private const val FENCE = "```"
    private const val BOLD = "**"
    private const val CODE = '`'
}
