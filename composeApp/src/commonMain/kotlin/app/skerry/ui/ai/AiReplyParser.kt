package app.skerry.ui.ai

/**
 * Pure parser turning the terminal AI bar's model reply ([TerminalAiController.commandPrompt]) into
 * a command suggestion or a message. Security-critical: [sanitizeCommand]/[isSafeInputChar] ensure
 * the command a user confirms is a single line with no control or bidi/zero-width characters.
 */
internal object AiReplyParser {

    /** Result of parsing a model reply. */
    sealed interface Reply {
        /** A command awaiting confirmation; [info] is an optional short description. */
        data class Command(val command: String, val info: String?) : Reply

        /** A clarification/refusal (ASK line or `#` comment); `null` [text] means ask the user to clarify. */
        data class Ask(val text: String?) : Reply

        /** A plain-prose reply (not a command); shown as a message. */
        data class Prose(val text: String) : Reply

        /** The model returned nothing usable. */
        data object NoCommand : Reply
    }

    /**
     * Parses a model reply: either `CMD:`+`INFO:` (a command) or `ASK:` (a clarification/refusal,
     * not a command). Without markers, falls back to treating the first line as a command unless
     * it reads like prose (Cyrillic/question/clarifying phrase), in which case it's a message.
     */
    fun parse(raw: String): Reply {
        val cmdLine = lineAfter(raw, "CMD:")
        val askLine = lineAfter(raw, "ASK:")
        val command = cmdLine?.let { sanitizeCommand(it) }
        return when {
            command != null && !command.startsWith("#") && !looksLikeProse(command) ->
                Reply.Command(command, lineAfter(raw, "INFO:")?.let { cleanLine(it) })
            askLine != null -> Reply.Ask(cleanLine(askLine))
            else -> {
                val first = sanitizeCommand(raw)
                when {
                    first == null -> Reply.NoCommand
                    first.startsWith("#") -> Reply.Ask(first.trimStart('#').trim().ifEmpty { null })
                    looksLikeProse(first) -> Reply.Prose(first)
                    else -> Reply.Command(first, extractDescription(raw))
                }
            }
        }
    }

    /**
     * Reduces raw model output to a single input line with no control characters or markdown
     * fences. The confirmed command can never carry a newline (otherwise `send` would auto-execute
     * it), even if the model returned multiline text or a CR/LF injection.
     *
     * Strips a ```-fence (with an optional language tag), takes the first non-blank line, filters
     * control bytes (except tab), then trims surrounding inline backticks — otherwise bash would
     * treat `` `free -h` `` as command substitution. Returns `null` if there is no command.
     */
    fun sanitizeCommand(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
            text = text.substring(3, text.length - 3)
            val firstTok = text.substringBefore('\n').trim()
            // ```bash / ```sh — language tag on the fence's first line, dropped.
            if (firstTok.isNotEmpty() && firstTok.none { it.isWhitespace() } &&
                firstTok.all { it.isLetterOrDigit() || it == '-' }
            ) {
                text = text.substringAfter('\n', "")
            }
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = firstLine.filter { isSafeInputChar(it) }.trim().trim('`').trim()
        return cleaned.ifEmpty { null }
    }

    /**
     * Whether a character is allowed in a command/description. Beyond control bytes (< 0x20 except
     * tab), also strips Unicode bidi/format characters (RTL/LTR override and isolate, zero-width,
     * BOM, soft hyphen) — otherwise a Trojan-Source string could render one way in the confirm bar
     * and execute differently in the PTY.
     */
    fun isSafeInputChar(c: Char): Boolean {
        if (c != '\t' && c.code < 0x20) return false
        val code = c.code
        val unsafeFormat = code == 0x00AD ||          // soft hyphen
            code == 0x061C ||                          // arabic letter mark
            code in 0x200B..0x200F ||                  // ZWSP/ZWNJ/ZWJ/LRM/RLM
            code == 0x2028 || code == 0x2029 ||        // line/paragraph separator
            code == 0x2060 ||                          // word joiner
            code in 0x202A..0x202E ||                  // bidi embeddings/overrides
            code in 0x2066..0x2069 ||                  // bidi isolates
            code == 0xFEFF                             // ZWNBSP / BOM
        return !unsafeFormat
    }

    /**
     * Second non-blank line of the reply: a short description of what the command does, with
     * list/`#`/backtick markers stripped, capped at 120 chars. `null` if there is no description.
     */
    fun extractDescription(raw: String): String? {
        val lines = raw.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val desc = lines.getOrNull(1) ?: return null
        val cleaned = desc.trimStart('#', '-', '*', '•', '>').trim().trim('`').trim()
            .filter { isSafeInputChar(it) }.trim()
        return cleaned.ifEmpty { null }?.take(120)
    }

    /**
     * Whether a string reads like natural language (a clarification/refusal) rather than a shell
     * command: Cyrillic, a trailing "?", or common clarifying phrases. Fallback for when the model
     * omits `ASK:`.
     */
    fun looksLikeProse(s: String): Boolean {
        if (s.endsWith("?")) return true
        if (s.any { it in 'Ѐ'..'ӿ' }) return true
        val lower = s.lowercase()
        return PROSE_STARTERS.any { lower.startsWith(it) }
    }

    /** First line starting with [prefix] (case-insensitive); returns the remainder, or `null` if none. */
    private fun lineAfter(raw: String, prefix: String): String? {
        raw.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith(prefix, ignoreCase = true)) {
                return t.substring(prefix.length).trim().ifEmpty { null }
            }
        }
        return null
    }

    /** Cleans an INFO/ASK line: backticks, list markers, control bytes; capped at 160 chars. */
    private fun cleanLine(s: String): String? {
        val c = s.trim().trim('`').trimStart('#', '-', '*', '•', '>').trim()
            .filter { isSafeInputChar(it) }.trim()
        return c.ifEmpty { null }?.take(160)
    }

    private val PROSE_STARTERS = listOf(
        "please", "sorry", "could you", "can you", "which ", "what ", "i cannot", "i can't",
        "i'm ", "i am ", "unable", "clarify", "specify", "you need", "the request", "to run this",
    )
}
