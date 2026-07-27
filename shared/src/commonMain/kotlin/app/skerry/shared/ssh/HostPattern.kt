package app.skerry.shared.ssh

/**
 * OpenSSH-style host pattern lists, as written next to a `@cert-authority` entry in `known_hosts`:
 * comma-separated elements, `*`/`?` wildcards, a leading `!` to exclude. A host matches when some
 * positive element matches it and no negated one does — so `*.example.com,!admin.example.com`
 * covers the fleet but not the admin box, and a list of negations alone matches nothing.
 *
 * Two deliberate departures from the file format, because this is a pattern a user types into a
 * field rather than a line OpenSSH parses:
 *  - comparison is case-insensitive (DNS names are);
 *  - a bare pattern applies to **any** port, while OpenSSH reads it as port 22 only. A CA is
 *    trusted for a fleet, and fleets run SSH on more than one port; the bracketed form
 *    (`[*.example.com]:2222`) still pins a single port for anyone who wants that.
 */
object HostPattern {

    /** A real host pattern is short; a longer element is ignored rather than matched — see [globMatches]. */
    const val MAX_PATTERN_LENGTH: Int = 256

    /** Upper bound on elements in one list, so a pasted line can't turn one match into thousands. */
    const val MAX_ELEMENTS: Int = 64

    /** Whether [host]:[port] matches the pattern list [patterns] (see the class doc for the rules). */
    fun matches(patterns: String, host: String, port: Int): Boolean {
        val target = host.lowercase()
        var matched = false
        var seen = 0
        for (raw in patterns.split(',')) {
            if (++seen > MAX_ELEMENTS) break
            val element = raw.trim()
            if (element.isEmpty()) continue
            val negated = element.startsWith("!")
            val body = if (negated) element.substring(1) else element
            if (body.isEmpty() || body.length > MAX_PATTERN_LENGTH) continue
            val (pattern, boundPort) = splitPort(body) ?: continue
            if (boundPort != null && boundPort != port) continue
            if (!globMatches(pattern.lowercase(), target)) continue
            // A negated element wins outright, wherever it sits in the list.
            if (negated) return false
            matched = true
        }
        return matched
    }

    /**
     * Whether [patterns] can ever match a host: it needs at least one usable positive element.
     * A list of negations alone, or one whose only positive element is malformed or over
     * [MAX_PATTERN_LENGTH] (both silently skipped by [matches]), would sit in the UI looking like
     * trust while covering nothing. Kept here, next to [matches], so both read the same rules.
     */
    fun coversAnyHost(patterns: String): Boolean =
        elements(patterns).any { !it.startsWith("!") && it.length <= MAX_PATTERN_LENGTH && splitPort(it) != null }

    /**
     * Canonical form for storage and comparison: elements trimmed, lowercased (hosts are matched
     * case-insensitively), blanks dropped. Without it `*.Example.com ` and `*.example.com` would be
     * stored as two entries that cover exactly the same hosts.
     */
    fun normalize(patterns: String): String =
        elements(patterns).joinToString(",") { it.lowercase() }

    private fun elements(patterns: String): List<String> =
        patterns.split(',').take(MAX_ELEMENTS).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Splits the `[pattern]:port` form into its parts; a bare pattern yields a null port ("any").
     * Returns null for a malformed bracketed element — it names no host we could match against, and
     * treating it as a bare pattern would silently widen what a CA covers.
     */
    private fun splitPort(element: String): Pair<String, Int?>? {
        if (!element.startsWith("[")) return element to null
        val close = element.lastIndexOf("]:")
        if (close <= 1) return null
        val pattern = element.substring(1, close)
        val port = element.substring(close + 2).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return pattern to port
    }
}

/**
 * OpenSSH glob: `*` matches any run (including empty), `?` matches exactly one character;
 * everything else is literal. Deliberately a two-pointer matcher rather than a translated regex:
 * patterns come from a file or a paste, and a regex built from many `*` (`.*.*.*…`) is the classic
 * catastrophic-backtracking (ReDoS) shape on the JVM engine. This algorithm backtracks only the
 * last `*`, so many wildcards can't blow up; its remaining worst case (a `*` followed by a long
 * literal that keeps failing) is O(pattern × value), which is why callers cap the pattern length.
 */
internal fun globMatches(pattern: String, value: String): Boolean {
    var p = 0
    var v = 0
    var star = -1
    var afterStar = 0
    while (v < value.length) {
        when {
            p < pattern.length && (pattern[p] == '?' || pattern[p] == value[v]) -> { p++; v++ }
            p < pattern.length && pattern[p] == '*' -> { star = p; afterStar = v; p++ }
            star != -1 -> { p = star + 1; afterStar++; v = afterStar }
            else -> return false
        }
    }
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}
