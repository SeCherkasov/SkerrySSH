package app.skerry.shared.terminal.highlight

/** Columns past which a line is noise, not a log record — scanning further buys nothing. */
const val MAX_OUTPUT_SCAN = 512

/** Level words, upper-cased. Matched only when the token also *looks* like a label — see [levelAt]. */
private val LEVEL_WORDS: Map<String, HighlightKind> = mapOf(
    "ERROR" to HighlightKind.LevelError,
    "ERR" to HighlightKind.LevelError,
    "FATAL" to HighlightKind.LevelError,
    "CRITICAL" to HighlightKind.LevelError,
    "CRIT" to HighlightKind.LevelError,
    "PANIC" to HighlightKind.LevelError,
    "FAIL" to HighlightKind.LevelError,
    "FAILED" to HighlightKind.LevelError,
    "WARN" to HighlightKind.LevelWarn,
    "WARNING" to HighlightKind.LevelWarn,
    "INFO" to HighlightKind.LevelInfo,
    "NOTICE" to HighlightKind.LevelInfo,
    "DEBUG" to HighlightKind.LevelDebug,
    "TRACE" to HighlightKind.LevelDebug,
    "OK" to HighlightKind.LevelOk,
    "SUCCESS" to HighlightKind.LevelOk,
    "PASS" to HighlightKind.LevelOk,
    "PASSED" to HighlightKind.LevelOk,
)

/** Longest key in [LEVEL_WORDS] — the scan reads no further before giving up on a token. */
private const val MAX_LEVEL_LENGTH = 8

/** Characters that may sit directly before a level word: a bracket, a quote, or a field separator. */
private const val LABEL_OPENERS = "[(<{\"'|,"

/** Characters that may sit directly after a level word for it to still read as a label. */
private const val LABEL_CLOSERS = "])>}\"'|,:="

/**
 * Characters that end a numeric token. Unlike [LABEL_CLOSERS] this excludes `:` — a colon is part of
 * both a clock (`10:11:12`) and a `host:port`, and cutting there would leave a bare `10`.
 */
private const val NUMERIC_CLOSERS = "])>}\"',|"

/**
 * Marks the few things worth spotting in output a command printed without colors of its own: log
 * levels, IPv4 addresses and timestamps.
 *
 * Level words match only on a token boundary and only when they look like a label — all caps
 * (`ERROR failed to bind`), followed by a colon (`error: no such file`), or bracketed (`[warn]`).
 * That keeps prose ("an error occurred while parsing") out of the results.
 *
 * One left-to-right scan, no regex: this runs for every visible row of every snapshot.
 */
fun highlightOutputLine(text: String, limit: Int = MAX_OUTPUT_SCAN): List<HighlightSpan> {
    val end = minOf(text.length, limit)
    var out: MutableList<HighlightSpan>? = null
    var i = 0
    while (i < end) {
        val ch = text[i]
        val span = when {
            !isTokenStart(text, i) -> null
            ch.isDigit() -> numericAt(text, i, end)
            ch.isLetter() -> levelAt(text, i, end)
            else -> null
        }
        if (span == null) { i++; continue }
        (out ?: ArrayList<HighlightSpan>(4).also { out = it }).add(span)
        i = span.endExclusive
    }
    return out ?: emptyList()
}

/** Whether position [at] begins a token: start of line, whitespace, or a label opener before it. */
private fun isTokenStart(text: String, at: Int): Boolean {
    if (at == 0) return true
    val prev = text[at - 1]
    return prev.isWhitespace() || prev in LABEL_OPENERS
}

/**
 * A level word starting at [from], or `null`. Requires both a token boundary at the end and one of
 * the label shapes: all-caps, a trailing `:`/bracket, or a bracket right before it.
 */
private fun levelAt(text: String, from: Int, end: Int): HighlightSpan? {
    var wordEnd = from
    while (wordEnd < end && text[wordEnd].isLetter()) {
        // Bail out early rather than uppercase a whole English sentence word by word.
        if (wordEnd - from > MAX_LEVEL_LENGTH) return null
        wordEnd++
    }
    val word = text.substring(from, wordEnd)
    val kind = LEVEL_WORDS[word.uppercase()] ?: return null
    val after = text.getOrNull(wordEnd)
    val closer = after != null && after in LABEL_CLOSERS
    if (!(after == null || after.isWhitespace() || closer)) return null
    val labelled = word.all { it.isUpperCase() } ||
        closer ||
        (from > 0 && text[from - 1] in LABEL_OPENERS)
    return if (labelled) HighlightSpan(from, wordEnd, kind) else null
}

/** An IPv4 address (optionally `:port`), a clock time or an ISO date starting at [from], or `null`. */
private fun numericAt(text: String, from: Int, end: Int): HighlightSpan? {
    var tokenEnd = from
    while (tokenEnd < end && !text[tokenEnd].isWhitespace() && text[tokenEnd] !in NUMERIC_CLOSERS) tokenEnd++
    val token = text.substring(from, tokenEnd)
    ipv4Length(token)?.let { return HighlightSpan(from, from + it, HighlightKind.Address) }
    timestampLength(token)?.let { return HighlightSpan(from, from + it, HighlightKind.Timestamp) }
    return null
}

/**
 * Length of the IPv4 address (plus an optional `:port`) [token] starts with, or `null`. Exactly four
 * octets of 0..255 and nothing but a port after them: `1.2.3.4.5` and `999.1.1.1` are not addresses,
 * and neither is a version number.
 */
internal fun ipv4Length(token: String): Int? {
    var i = 0
    repeat(4) { octet ->
        if (octet > 0) {
            if (token.getOrNull(i) != '.') return null
            i++
        }
        i = octetEnd(token, i) ?: return null
    }
    val afterHost = i
    i = portEnd(token, i) ?: afterHost
    // A trailing '.' is sentence punctuation; anything else means the token wasn't an address.
    val tail = token.getOrNull(i) ?: return i
    return if (tail == '.' && i == token.length - 1) i else null
}

/** End of a 1..3 digit octet of value 0..255 starting at [from], or `null` if there isn't one. */
private fun octetEnd(token: String, from: Int): Int? {
    var i = from
    while (i < token.length && token[i].isDigit()) i++
    val digits = i - from
    if (digits == 0 || digits > 3) return null
    return if (token.substring(from, i).toInt() <= 255) i else null
}

/** End of a `:port` suffix starting at [from], or `null` when there is no complete one. */
private fun portEnd(token: String, from: Int): Int? {
    if (token.getOrNull(from) != ':') return null
    var i = from + 1
    while (i < token.length && token[i].isDigit()) i++
    return if (i > from + 1) i else null
}

/**
 * Length of the timestamp [token] starts with, or `null`: `HH:MM:SS` with an optional `.mmm`
 * fraction, or an ISO `YYYY-MM-DD` date.
 */
internal fun timestampLength(token: String): Int? {
    clockLength(token)?.let { return it }
    return isoDateLength(token)
}

private fun clockLength(token: String): Int? {
    if (token.length < 8) return null
    for (i in intArrayOf(0, 1, 3, 4, 6, 7)) if (!token[i].isDigit()) return null
    if (token[2] != ':' || token[5] != ':') return null
    if (token[0].digitToInt() > 2 || token[3].digitToInt() > 5 || token[6].digitToInt() > 5) return null
    var end = 8
    if (end < token.length && token[end] == '.') {
        var i = end + 1
        while (i < token.length && token[i].isDigit()) i++
        if (i > end + 1) end = i
    }
    return end
}

private fun isoDateLength(token: String): Int? {
    if (token.length < 10) return null
    for (i in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9)) if (!token[i].isDigit()) return null
    if (token[4] != '-' || token[7] != '-') return null
    val month = token.substring(5, 7).toInt()
    val day = token.substring(8, 10).toInt()
    if (month !in 1..12 || day !in 1..31) return null
    return 10
}
