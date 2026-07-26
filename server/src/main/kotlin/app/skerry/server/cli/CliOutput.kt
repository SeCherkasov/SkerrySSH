package app.skerry.server.cli

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Table and value formatting for `skerry-admin`. Pure functions — the runner only prints them. */

private val UTC_MINUTES: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

/**
 * Left-aligned columns padded to the widest cell; the last column is never padded so trailing
 * whitespace doesn't show up in pipes. Rows shorter than [headers] are filled with blanks.
 */
fun table(headers: List<String>, rows: List<List<String>>): String {
    if (headers.isEmpty()) return ""
    val widths = IntArray(headers.size) { column ->
        maxOf(headers[column].length, rows.maxOfOrNull { it.getOrElse(column) { "" }.length } ?: 0)
    }
    fun line(cells: List<String>) = headers.indices
        .joinToString("  ") { column ->
            val cell = cells.getOrElse(column) { "" }
            if (column == headers.lastIndex) cell else cell.padEnd(widths[column])
        }
        .trimEnd()
    return (listOf(line(headers)) + rows.map(::line)).joinToString("\n")
}

/** Two-column key/value block for single-object output (`stats`, `health`). */
fun keyValues(pairs: List<Pair<String, String>>): String {
    val width = pairs.maxOfOrNull { it.first.length } ?: 0
    return pairs.joinToString("\n") { (key, value) -> key.padEnd(width) + "  " + value }
}

/** Binary units, as the console shows them — a self-hosted operator compares this against `du`. */
fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    // Locale.ROOT: the operator's JVM locale must not turn "1.2 MiB" into "1,2 MiB" in output that
    // gets grepped and compared against `du`.
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.ROOT, pattern, value, units[unit])
}

/**
 * Coarse "how long ago", which is what a device list is read for. A timestamp in the future (clock
 * skew between the server and the CLI host) is reported as such instead of a negative age.
 */
fun relativeTime(timestamp: Long?, now: Long): String {
    if (timestamp == null) return "—"
    val delta = now - timestamp
    val minutes = delta / 60_000
    return when {
        delta < -60_000 -> "in the future"
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 48 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

/** Absolute timestamps are printed in UTC so they don't depend on the container's timezone. */
fun utcMinutes(timestamp: Long): String = UTC_MINUTES.format(Instant.ofEpochMilli(timestamp)) + " UTC"
