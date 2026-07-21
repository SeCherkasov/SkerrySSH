package app.skerry.shared.terminal

/**
 * One scored fuzzy match: [score] (higher is better) and the matched character [positions] in the
 * candidate, for highlighting. Positions are empty for a blank query, which matches everything.
 */
data class FuzzyHit<T>(val item: T, val score: Int, val positions: List<Int>)

/**
 * Score [candidate] against [query] as a subsequence match (like a command palette, not a substring
 * search): every query character must appear in order, case-insensitively. Returns `null` when it
 * doesn't match at all.
 *
 * Scoring favours what a person means by "closest": runs of consecutive characters, matches at word
 * starts (string start or after a separator), and — all else equal — shorter candidates. Pure and
 * deterministic; the greedy left-to-right scan is O(candidate) rather than an optimal alignment,
 * which is enough for history-sized inputs and keeps the result easy to reason about.
 */
fun fuzzyScore(candidate: String, query: String): FuzzyHit<String>? {
    val needle = query.trim()
    if (needle.isEmpty()) return FuzzyHit(candidate, 0, emptyList())

    val positions = ArrayList<Int>(needle.length)
    var score = 0
    var at = 0
    var previousMatch = -2
    for (wanted in needle) {
        val found = candidate.indexOfIgnoringCase(wanted, at) ?: return null
        score += CHARACTER_BONUS
        if (found == previousMatch + 1) score += CONSECUTIVE_BONUS
        if (found == 0 || candidate[found - 1].isSeparator()) score += WORD_START_BONUS
        score -= (found - at).coerceAtMost(MAX_GAP_PENALTY)
        positions += found
        previousMatch = found
        at = found + 1
    }
    // Break ties towards shorter candidates: "df -h" over "df -h | sort … | head -20".
    score -= (candidate.length - needle.length).coerceAtMost(MAX_LENGTH_PENALTY)
    return FuzzyHit(candidate, score, positions)
}

/**
 * Rank [items] against [query] by [text], dropping non-matches. Ties keep input order, so a
 * newest-first history stays newest-first among equally good matches. A blank query returns
 * everything untouched.
 */
fun <T> fuzzyRank(items: List<T>, query: String, text: (T) -> String): List<FuzzyHit<T>> {
    if (query.isBlank()) return items.map { FuzzyHit(it, 0, emptyList()) }
    return items
        .mapNotNull { item -> fuzzyScore(text(item), query)?.let { FuzzyHit(item, it.score, it.positions) } }
        .sortedByDescending { it.score } // sortedByDescending is stable, so ties keep input order
}

private const val CHARACTER_BONUS = 8
private const val CONSECUTIVE_BONUS = 10
private const val WORD_START_BONUS = 6
private const val MAX_GAP_PENALTY = 5
private const val MAX_LENGTH_PENALTY = 6

private fun Char.isSeparator(): Boolean = this == ' ' || this == '-' || this == '_' || this == '/' || this == '.'

private fun String.indexOfIgnoringCase(char: Char, from: Int): Int? {
    for (i in from until length) if (this[i].equals(char, ignoreCase = true)) return i
    return null
}
