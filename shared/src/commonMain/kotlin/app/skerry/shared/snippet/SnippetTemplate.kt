package app.skerry.shared.snippet

import app.skerry.shared.terminal.isSafeTerminalInputChar

/**
 * Kind of a dynamic `${{…}}` variable in a snippet command (issue #58, XTerminal-compatible
 * syntax). Machine kinds (DATE…RANDOM) resolve from [SnippetRunEnvironment] alone; context kinds
 * need the UI: CLIPBOARD reads the system clipboard, VAULT looks up a credential by name, PARAM
 * prompts the user at run time.
 */
enum class SnippetVariableKind { DATE, TIME, TIMESTAMP, UUID, RANDOM, CLIPBOARD, VAULT, PARAM }

/** One piece of a parsed snippet command: literal text or a dynamic variable. */
sealed interface SnippetSegment {
    data class Literal(val text: String) : SnippetSegment

    /**
     * A `${{name}}` / `${{name:format}}` placeholder. [format]'s meaning depends on
     * [kind]: date/time token pattern, RANDOM length, VAULT entry name, PARAM default value
     * (`null` when omitted or empty). [raw] is the original placeholder text for previews/errors.
     */
    data class Variable(
        val kind: SnippetVariableKind,
        val name: String,
        val format: String?,
        val raw: String,
    ) : SnippetSegment
}

/** Local wall-clock moment a snippet run resolves date/time/timestamp against. */
data class SnippetMoment(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val epochSeconds: Long,
)

/**
 * Machine-value providers for one snippet run, captured once when the run is initiated so the
 * previewed command is exactly the one sent (see the TOCTOU rule in coding-guidelines §3).
 * [randomChars] returns the given number of characters from a cryptographically secure source.
 */
class SnippetRunEnvironment(
    val moment: SnippetMoment,
    val newUuid: () -> String,
    val randomChars: (Int) -> String,
)

/**
 * Parser and machine-side resolver for dynamic snippet variables. Pure logic — clipboard, vault
 * and user-prompted parameters are resolved by the UI layer on top of [parse].
 *
 * Syntax: `${{name}}` or `${{name:format}}`, name = letter followed by letters, digits,
 * `_` or `-`. Anything malformed (unterminated, invalid name, empty braces) stays literal text,
 * so ordinary shell syntax (`$VAR`, `${VAR}`, `$(cmd)`, `$$`) is never touched. Builtin
 * names are matched case-sensitively; any other valid name is a user parameter prompted at run
 * time. There is no escape sequence in v1 — `${{` always opens a placeholder.
 */
object SnippetTemplate {

    fun parse(command: String): List<SnippetSegment> {
        val out = mutableListOf<SnippetSegment>()
        val literal = StringBuilder()
        var i = 0
        while (i < command.length) {
            val variable = if (command.startsWith(OPEN, i)) variableAt(command, i) else null
            if (variable == null) {
                literal.append(command[i])
                i++
                continue
            }
            if (literal.isNotEmpty()) {
                out += SnippetSegment.Literal(literal.toString())
                literal.clear()
            }
            out += variable
            i += variable.raw.length
        }
        if (literal.isNotEmpty()) out += SnippetSegment.Literal(literal.toString())
        return out
    }

    fun hasVariables(command: String): Boolean = parse(command).any { it is SnippetSegment.Variable }

    fun variables(command: String): List<SnippetSegment.Variable> =
        parse(command).filterIsInstance<SnippetSegment.Variable>()

    /**
     * Resolves a machine variable (date/time/timestamp/uuid/random) against [env]; `null` for
     * context kinds (clipboard/vault/param) — those are the caller's job.
     */
    fun resolveMachine(variable: SnippetSegment.Variable, env: SnippetRunEnvironment): String? =
        when (variable.kind) {
            SnippetVariableKind.DATE -> formatMoment(variable.format ?: "YYYY-MM-DD", env.moment)
            SnippetVariableKind.TIME -> formatMoment(variable.format ?: "HH:mm:ss", env.moment)
            SnippetVariableKind.TIMESTAMP -> env.moment.epochSeconds.toString()
            SnippetVariableKind.UUID -> env.newUuid()
            SnippetVariableKind.RANDOM ->
                env.randomChars((variable.format?.toIntOrNull() ?: DEFAULT_RANDOM_LENGTH).coerceIn(1, MAX_RANDOM_LENGTH))
            else -> null
        }

    /**
     * Machine values per segment, index-aligned with [segments] (`null` for literals and context
     * variables). Computed once per run and passed to every [assemble] call — uuid/random draw
     * fresh values on each provider call, so previewing and sending must share one draw or the
     * user would confirm one line and send another.
     */
    fun machineValues(segments: List<SnippetSegment>, env: SnippetRunEnvironment): List<String?> =
        segments.map { segment -> (segment as? SnippetSegment.Variable)?.let { resolveMachine(it, env) } }

    /**
     * Builds the command line: literals pass through unchanged (a multi-line snippet stays
     * multi-line), machine variables come from [machineValues], everything else is asked from
     * [contextValue] (clipboard/vault/param, already collected by the UI). Context values are
     * sanitized here — at the single choke point — so no untrusted value can smuggle a newline or
     * bidi text into the line the user confirmed (see [sanitizeSnippetValue]).
     */
    fun assemble(
        segments: List<SnippetSegment>,
        machineValues: List<String?>,
        contextValue: (SnippetSegment.Variable) -> String,
    ): String = buildString {
        segments.forEachIndexed { i, segment ->
            when (segment) {
                is SnippetSegment.Literal -> append(stripUnsafeFormatChars(segment.text))
                is SnippetSegment.Variable ->
                    append(machineValues.getOrNull(i) ?: sanitizeSnippetValue(contextValue(segment)))
            }
        }
    }

    /** One-shot [machineValues] + [assemble] for runs that need no preview step. */
    fun resolve(
        segments: List<SnippetSegment>,
        env: SnippetRunEnvironment,
        contextValue: (SnippetSegment.Variable) -> String,
    ): String = assemble(segments, machineValues(segments, env), contextValue)

    /**
     * Renders a moment through a token pattern (`YYYY` `YY` `MM` `DD` `HH` `mm` `ss`, zero-padded;
     * longest token wins, everything else passes through literally).
     */
    fun formatMoment(pattern: String, moment: SnippetMoment): String = buildString {
        var i = 0
        while (i < pattern.length) {
            val token = TOKENS.firstOrNull { pattern.startsWith(it.first, i) }
            if (token == null) {
                append(pattern[i])
                i++
            } else {
                append(token.second(moment))
                i += token.first.length
            }
        }
    }

    private fun variableAt(command: String, start: Int): SnippetSegment.Variable? {
        val end = command.indexOf(CLOSE, start + OPEN.length)
        if (end < 0) return null
        val inner = command.substring(start + OPEN.length, end)
        val name = inner.substringBefore(':')
        if (!isValidName(name)) return null
        val format = inner.substringAfter(':', missingDelimiterValue = "").ifEmpty { null }
        val kind = kindFor(name)
        // A vault reference without an entry name is an authoring mistake, not a lookup for a
        // credential labeled "" — treat it as literal text like the other malformed cases.
        if (kind == SnippetVariableKind.VAULT && format == null) return null
        return SnippetSegment.Variable(
            kind = kind,
            name = name,
            format = format,
            raw = command.substring(start, end + CLOSE.length),
        )
    }

    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.first().isLetter() &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun kindFor(name: String): SnippetVariableKind = when (name) {
        "date" -> SnippetVariableKind.DATE
        "time" -> SnippetVariableKind.TIME
        "timestamp" -> SnippetVariableKind.TIMESTAMP
        "uuid" -> SnippetVariableKind.UUID
        "random" -> SnippetVariableKind.RANDOM
        "clipboard" -> SnippetVariableKind.CLIPBOARD
        "vault" -> SnippetVariableKind.VAULT
        else -> SnippetVariableKind.PARAM
    }

    private const val OPEN = "\${{"
    private const val CLOSE = "}}"
    private const val DEFAULT_RANDOM_LENGTH = 8
    private const val MAX_RANDOM_LENGTH = 64

    private fun pad2(n: Int) = n.toString().padStart(2, '0')

    private val TOKENS: List<Pair<String, (SnippetMoment) -> String>> = listOf(
        "YYYY" to { m -> m.year.toString().padStart(4, '0') },
        "YY" to { m -> pad2(m.year % 100) },
        "MM" to { m -> pad2(m.month) },
        "DD" to { m -> pad2(m.day) },
        "HH" to { m -> pad2(m.hour) },
        "mm" to { m -> pad2(m.minute) },
        "ss" to { m -> pad2(m.second) },
    )
}

/**
 * Removes bidi/format (and DEL/C1) characters from literal template text while keeping its
 * whitespace and newlines: a snippet can arrive via Teams sharing, so the literal part is not
 * trusted to render the same way it executes (Trojan Source in the preview/palette vs the PTY) —
 * but an intentionally multi-line script must stay multi-line. C0 bytes below 0x20 are kept:
 * they are the user's own saved text, not a rendering trick.
 */
fun stripUnsafeFormatChars(text: String): String =
    text.filter { it.code < 0x20 || isSafeTerminalInputChar(it) }

/**
 * Flattens an untrusted variable value (clipboard contents, vault secret, user parameter) into
 * text that is safe to splice into a single command line: newlines and tabs become spaces
 * (an embedded newline would execute the command early, before the previewed line was complete),
 * remaining control and bidi/format characters are dropped (see [isSafeTerminalInputChar]).
 */
fun sanitizeSnippetValue(raw: String): String = buildString(raw.length) {
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') {
            append(' ')
            i += 2
            continue
        }
        when {
            c == '\r' || c == '\n' || c == '\t' -> append(' ')
            isSafeTerminalInputChar(c) -> append(c)
        }
        i++
    }
}
