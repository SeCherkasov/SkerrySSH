package app.skerry.ui.ai

import app.skerry.shared.terminal.isSafeDisplayChar

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
 * newline or control byte and execute more than the block displays. The block's *displayed* text is
 * filtered too — it is what the confirmation UI shows, and since the panel became selectable, what a
 * copy hands to the clipboard.
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
            val block = buffer.joinToString("\n").trim()
            buffer.clear()
            if (block.isEmpty()) return
            // Both kinds are filtered before they are shown, not only before they are run. The card
            // displays a block's text while Run sends the line out of [commands]; a bidi override
            // left in the shown copy would render the line in an order the shell never sees — the
            // Trojan Source case [isSafeTerminalInputChar] exists for. Prose gets the same treatment
            // because it is not inert either: [spans] renders a `…` run in the mono font and its
            // closing backtick may be lines away, so prose can look exactly like the command card
            // beside it. Since the panel became selectable, every one of these is one Ctrl+C and one
            // paste from the shell, and a paste is not filtered.
            // A block keeps the strict input predicate: its text sits next to Run, so displayed and
            // executed have to be the same bytes. Prose keeps its joiners — see [safeText].
            val text = if (fenced) filter(block, AiReplyParser::isSafeInputChar) else safeText(block)
            // A run that was nothing but rejected characters is dropped rather than shown empty.
            // Deliberate, and worth knowing when a report says a reply "lost a block": the filter
            // took it, and the model wrote nothing a reader could have acted on anyway.
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
     * Model prose as it may be shown: the characters that would render a line in an order it is not
     * written in are dropped ([isSafeDisplayChar]), the rest is kept — a reply is one copy away from
     * a shell, but it is also where a family emoji or a Persian word lives, and the input predicate
     * would take those apart.
     *
     * Also the entry point for the surfaces that never reach [segments]: the quick chat renders a
     * whole reply as one string, fences and all, and the mobile bar shows an explanation the same
     * way. Trimmed again afterwards — [segments] trims the raw text, and a line of nothing but U+200B
     * is not whitespace to `String.trim()`, so filtering can leave a blank line behind.
     */
    fun safeText(raw: String): String = filter(raw, ::isSafeDisplayChar)

    /** Applies [allowed] to every line, keeping the line structure, and trims what is left. */
    private fun filter(raw: String, allowed: (Char) -> Boolean): String = raw.lineSequence()
        .joinToString("\n") { line -> line.filter(allowed).trimEnd() }
        .trim()

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
