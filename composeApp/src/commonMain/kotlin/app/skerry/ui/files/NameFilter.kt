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

/** Glob → [Regex]: escape everything, then turn `*`/`?` into their regex forms. */
private fun globToRegex(glob: String): Regex {
    val pattern = buildString {
        for (ch in glob) {
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(ch.toString()))
            }
        }
    }
    return Regex(pattern, RegexOption.IGNORE_CASE)
}
