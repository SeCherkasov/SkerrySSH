package app.skerry.shared.snippet

import app.skerry.shared.terminal.isSafeTerminalInputChar
import app.skerry.shared.text.INVISIBLE_LETTERS

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

/**
 * What makes two placeholders the same variable to a caller that asks for values once and reuses
 * them: kind, name and format. [SnippetSegment.Variable.raw] is deliberately left out — `${'$'}{{svc}}`
 * and `${'$'}{{svc:}}` parse to the same variable with different original text, and keying by the whole
 * segment would ask for one and fail to find it for the other.
 */
fun SnippetSegment.Variable.identity(): String = kind.name + "/" + name + "/" + format

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
 * [randomChars] returns the given number of characters drawn uniformly from the given non-empty
 * alphabet by a cryptographically secure source.
 */
class SnippetRunEnvironment(
    val moment: SnippetMoment,
    val newUuid: () -> String,
    val randomChars: (Int, String) -> String,
)

/**
 * Alphabets for `${'$'}{{random}}`. Every character must be safe to splice unquoted into the
 * confirmed command line — no shell metacharacters, quotes, whitespace, or glob/expansion
 * triggers (see the shell-safety test). [DEFAULT] is the historic alphabet used when no charset
 * is named, kept unchanged so existing snippets keep their output shape.
 */
object SnippetRandomAlphabets {
    const val DEFAULT = "abcdefghijklmnopqrstuvwxyz0123456789"
    const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ$DEFAULT"
    const val HEX = "0123456789abcdef"

    // No `#` (starts a shell comment at word start — the confirmed line would run truncated),
    // no `^` (bash history substitution at line start), no `=` (a draw containing it, spliced as
    // the line's first word, parses as a variable assignment; zsh aborts on word-initial `=`).
    // Position-dependent, so a per-draw check can't save them: a value must be inert wherever
    // the template splices it.
    const val SPECIAL = "$ALNUM@%_+-:,./"

    /** Alphabet for a `${'$'}{{random:…}}` charset token, or `null` if the token names none. */
    fun forName(name: String): String? = when (name) {
        "alnum" -> ALNUM
        "hex" -> HEX
        "special" -> SPECIAL
        else -> null
    }
}

/**
 * Default value a PARAM prefills its prompt with: the format itself, or — when the format is an
 * option list — its first entry, sanitized like the options are (`null` when nothing of it
 * survives). Non-PARAM kinds have no default. A plain default (no `|`) is kept verbatim, as it
 * always was.
 */
fun SnippetSegment.Variable.paramDefault(): String? {
    if (kind != SnippetVariableKind.PARAM) return null
    val format = format ?: return null
    if ('|' !in format) return format
    return sanitizeSnippetValue(format.substringBefore('|').trim()).ifEmpty { null }
}

/**
 * Options a PARAM offers at run time (`${'$'}{{name:default|opt1|opt2}}`), each already in the form
 * that will be spliced ([sanitizeSnippetValue]): what the picker offers, what it shows as selected
 * and what the run sends must be one string, or a bidi/invisible character in a shared template
 * makes the user pick one thing and run another. An option nothing survives of is dropped, not
 * offered as a blank row (same rule the untrusted-label filter follows). Duplicates dropped; empty
 * when the format holds no `|`-separated list or the kind is not PARAM. The default, when present,
 * is always one of the options — it is the list's first surviving entry.
 */
fun SnippetSegment.Variable.paramChoices(): List<String> {
    if (kind != SnippetVariableKind.PARAM) return emptyList()
    val format = format ?: return emptyList()
    if ('|' !in format) return emptyList()
    return format.split('|').map { sanitizeSnippetValue(it.trim()) }.filter { it.isNotEmpty() }.distinct()
}

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
            SnippetVariableKind.RANDOM -> {
                val (length, alphabet) = randomSpec(variable.format)
                env.randomChars(length, alphabet)
            }
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
                // Machine values go through the same sanitizer as context ones. They look
                // machine-made, but `${{date:…}}`/`${{time:…}}` carry a caller-authored format
                // string that [formatMoment] copies through verbatim — and the template itself can
                // arrive over sync or Teams sharing. Without this, `${{date:x<newline>rm -rf ~}}`
                // would end the previewed line and start a command the user never confirmed.
                is SnippetSegment.Variable ->
                    append(sanitizeSnippetValue(machineValues.getOrNull(i) ?: contextValue(segment)))
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

    /**
     * `${'$'}{{random:…}}` format: comma-separated tokens, the first numeric one is the length,
     * the first known charset name picks the alphabet ([SnippetRandomAlphabets.forName]); order
     * is free and unknown tokens are ignored — garbage degrades to the defaults, same as the
     * historic length-only behavior.
     */
    private fun randomSpec(format: String?): Pair<Int, String> {
        var length: Int? = null
        var alphabet: String? = null
        for (token in format.orEmpty().split(',')) {
            val trimmed = token.trim()
            if (length == null) length = trimmed.toIntOrNull()
            if (alphabet == null) alphabet = SnippetRandomAlphabets.forName(trimmed)
        }
        return (length ?: DEFAULT_RANDOM_LENGTH).coerceIn(1, MAX_RANDOM_LENGTH) to
            (alphabet ?: SnippetRandomAlphabets.DEFAULT)
    }

    // The invisible letters are letters to `isLetterOrDigit`, so a shared template could carry two
    // names that draw and announce identically — one prompted, the other spliced from its inline
    // default without the user ever seeing a second field. Such a placeholder stays literal text.
    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.first().isLetter() &&
            name.all { (it.isLetterOrDigit() || it == '_' || it == '-') && it !in INVISIBLE_LETTERS }

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
    text.filter { (it.code < 0x20 || isSafeTerminalInputChar(it)) && it !in INVISIBLE_LETTERS }

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
            isSafeTerminalInputChar(c) && c !in INVISIBLE_LETTERS -> append(c)
        }
        i++
    }
}
