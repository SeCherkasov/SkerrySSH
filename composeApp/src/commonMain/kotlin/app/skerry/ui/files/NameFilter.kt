package app.skerry.ui.files

/**
 * Listing quick-filter predicate (mc/Total Commander style). A blank [filter] passes everything.
 * A filter containing `*`/`?` is a glob over the whole name (`*` — any run, `?` — one character);
 * anything else is a case-insensitive substring match. Regex metacharacters in the filter are
 * literal, so user input can never be a pattern syntax error.
 */
fun matchesNameFilter(name: String, filter: String): Boolean {
    val f = filter.trim()
    if (f.isEmpty()) return true
    if ('*' !in f && '?' !in f) return name.contains(f, ignoreCase = true)
    return globToRegex(f).matches(name)
}

/**
 * Glob → [Regex]: escape literals, turn `*`/`?` into their regex forms. A run of adjacent
 * wildcards is normalized to all its `?`s plus at most one `*` (`**` ≡ `*`, `*?*` ≡ `?*`) — the
 * match is equivalent, and it keeps a typed filter like `"****a"` from compiling to `.*.*.*.*a`,
 * whose backtracking blows up exponentially on non-matching names (the field filters a
 * server-supplied listing on every keystroke, on the UI thread).
 */
private fun globToRegex(glob: String): Regex {
    val pattern = buildString {
        var i = 0
        while (i < glob.length) {
            val ch = glob[i]
            if (ch == '*' || ch == '?') {
                var star = false
                while (i < glob.length && (glob[i] == '*' || glob[i] == '?')) {
                    if (glob[i] == '*') star = true else append('.')
                    i++
                }
                if (star) append(".*")
            } else {
                append(Regex.escape(ch.toString()))
                i++
            }
        }
    }
    return Regex(pattern, RegexOption.IGNORE_CASE)
}
