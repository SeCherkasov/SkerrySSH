package app.skerry.shared.terminal

/**
 * One row of the command palette: the [command], the host it came from ([hostLabel], `null` for
 * records written before labels existed), whether that host is the session the palette was opened
 * from, and the fuzzy-matched character [positions] for highlighting.
 */
data class CommandSuggestion(
    val command: String,
    val hostLabel: String?,
    val fromCurrentHost: Boolean,
    val positions: List<Int> = emptyList(),
)

/**
 * Build the palette list from every host's history: commands of the session in [currentKey] first
 * (newest-first), then everyone else's, deduplicated by command text — a command known to both the
 * current host and another is attributed to the current one, since that's where it can be run
 * without thinking. [query] then filters and reorders the result fuzzily ([fuzzyRank]); a blank
 * query keeps the recency order. Capped at [limit].
 *
 * Pure function: the caller supplies the records, so the palette is testable without a vault.
 */
fun commandSuggestions(
    records: List<TerminalHistoryRecord>,
    currentKey: String?,
    query: String,
    limit: Int = 200,
): List<CommandSuggestion> {
    val (current, others) = records.partition { currentKey != null && it.key == currentKey }
    val seen = LinkedHashMap<String, CommandSuggestion>()
    for ((record, isCurrent) in (current.map { it to true } + others.map { it to false })) {
        for (command in record.commands) {
            val trimmed = command.trim()
            if (trimmed.isEmpty() || trimmed in seen) continue
            seen[trimmed] = CommandSuggestion(trimmed, record.label, isCurrent)
        }
    }
    return fuzzyRank(seen.values.toList(), query) { it.command }
        .take(limit)
        .map { hit -> hit.item.copy(positions = hit.positions) }
}
