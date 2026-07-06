package app.skerry.ui.metrics

import kotlin.math.roundToInt

/**
 * Host state snapshot for the terminal info panel: resources (CPU/memory/disk) plus host facts
 * (uptime, load average, OS, kernel, CPU count) — all from one round-trip in
 * [HostMetricsController]. CPU/disk percentages are 0..100; memory is in bytes. Fractions
 * ([cpuFraction]/[memFraction]/[diskFraction]) are for progress bars (0..1). Fact fields are
 * optional: `null` if the corresponding section is missing from the output (old server,
 * non-Linux) — the UI then shows "…" instead of garbage.
 */
data class HostMetrics(
    val cpuPercent: Int,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val diskPercent: Int,
    val uptimeSeconds: Long? = null,
    val loadAverage: String? = null,
    val osName: String? = null,
    val kernel: String? = null,
    val cpuCount: Int? = null,
) {
    val cpuFraction: Float get() = (cpuPercent / 100f).coerceIn(0f, 1f)
    val memFraction: Float get() = if (memTotalBytes > 0) (memUsedBytes.toFloat() / memTotalBytes).coerceIn(0f, 1f) else 0f
    val diskFraction: Float get() = (diskPercent / 100f).coerceIn(0f, 1f)
}

/** Uptime seconds to `HH:MM:SS` (with an `Nd ` prefix if >= a day). Negative values clamp to zero. */
fun formatUptime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val days = s / 86_400
    val h = (s % 86_400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun pad(v: Long) = v.toString().padStart(2, '0')
    val hms = "${pad(h)}:${pad(m)}:${pad(sec)}"
    return if (days > 0) "${days}d $hms" else hms
}

/**
 * Parses the output of [HostMetricsController.METRICS_COMMAND] into [HostMetrics]. Format (Linux):
 *
 * ```
 * cpu  <jiffies…>        # first /proc/stat sample
 * cpu  <jiffies…>        # second sample (after a short pause)
 * @MEM
 * <free -b: Mem: total used … line>
 * @DISK
 * <df -Pk /: data line with a Use% column>
 * ```
 *
 * CPU is computed from the delta of two /proc/stat samples (non-idle fraction over the interval);
 * with a single sample, it's instantaneous since system start. Returns `null` if memory or disk
 * is missing from the output (e.g. a non-Linux server) — the UI then shows "no data" instead of garbage.
 */
fun parseHostMetrics(raw: String): HostMetrics? {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    // Each metric is looked up strictly within its own section (between its marker and the next
    // marker), so a stray %-token from a neighboring section (or another df mount line) doesn't
    // get picked up as the metric. CPU is the `cpu …` /proc/stat lines, always before @MEM.
    val cpuPercent = cpuPercentFromStat(lines.filter { it.startsWith("cpu ") })

    // Memory and disk are required: their absence signals non-Linux/truncated output -> null overall.
    val memSection = sectionOrAll(lines, "@MEM")
    val memLine = memSection.firstOrNull { it.startsWith("Mem:") } ?: return null
    val memTokens = memLine.split(WHITESPACE)
    val memTotal = memTokens.getOrNull(1)?.toLongOrNull() ?: return null
    val memUsed = memTokens.getOrNull(2)?.toLongOrNull() ?: return null

    val diskPercent = diskPercentFromDf(sectionOrAll(lines, "@DISK")) ?: return null

    // Host facts are optional: their sections may be absent (old server) — the field stays null then.
    val uptimeSeconds = section(lines, "@UPTIME").firstOrNull()
        ?.split(WHITESPACE)?.firstOrNull()?.toDoubleOrNull()?.toLong()
    val loadAverage = section(lines, "@LOAD").firstOrNull()
        ?.split(WHITESPACE)?.take(3)?.takeIf { it.size == 3 }?.joinToString(" ")
    // OS/kernel come from the remote (trusted but potentially misbehaving) host — cap the length
    // so an anomalously long string can't break the info panel layout (defence-in-depth).
    val osName = section(lines, "@OS").firstOrNull { it.startsWith("PRETTY_NAME=") }
        ?.removePrefix("PRETTY_NAME=")?.trim('"')?.take(FACT_MAX_LEN)?.takeIf { it.isNotBlank() }
    val kernel = section(lines, "@KERNEL").firstOrNull()?.take(FACT_MAX_LEN)?.takeIf { it.isNotBlank() }
    val cpuCount = section(lines, "@CPU").firstOrNull()?.toIntOrNull()

    return HostMetrics(
        cpuPercent = cpuPercent,
        memUsedBytes = memUsed,
        memTotalBytes = memTotal,
        diskPercent = diskPercent,
        uptimeSeconds = uptimeSeconds,
        loadAverage = loadAverage,
        osName = osName,
        kernel = kernel,
        cpuCount = cpuCount,
    )
}

private val WHITESPACE = Regex("\\s+")

/** Max length of string host facts (OS/kernel) from the remote server — protects the info panel layout. */
private const val FACT_MAX_LEN = 120

/** All output section markers — [section] uses these to find the boundary of "its" section. */
private val SECTION_MARKERS = setOf("@MEM", "@DISK", "@UPTIME", "@LOAD", "@OS", "@KERNEL", "@CPU")

/** Lines of section [marker]: from it (exclusive) to the next marker (or end). Empty if absent. */
private fun section(lines: List<String>, marker: String): List<String> {
    val start = lines.indexOf(marker)
    if (start < 0) return emptyList()
    val end = (start + 1 until lines.size).firstOrNull { lines[it] in SECTION_MARKERS } ?: lines.size
    return lines.subList(start + 1, end)
}

/** Like [section], but scans the whole output when the marker is absent — backward compatible with the markerless format. */
private fun sectionOrAll(lines: List<String>, marker: String): List<String> =
    if (marker in lines) section(lines, marker) else lines

/** total and idle (idle+iowait) jiffies from a `cpu …` /proc/stat line, or null if too few numbers. */
private fun cpuTotalsFromStatLine(line: String): Pair<Long, Long>? {
    val t = line.split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
    if (t.size < 5) return null
    return t.sum() to (t[3] + t[4])
}

private fun cpuPercentFromStat(cpuLines: List<String>): Int {
    if (cpuLines.size >= 2) {
        val a = cpuTotalsFromStatLine(cpuLines[0]) ?: return 0
        val b = cpuTotalsFromStatLine(cpuLines[1]) ?: return 0
        val deltaTotal = b.first - a.first
        val deltaIdle = b.second - a.second
        if (deltaTotal <= 0) return 0
        return (100.0 * (deltaTotal - deltaIdle) / deltaTotal).roundToInt().coerceIn(0, 100)
    }
    val one = cpuLines.firstOrNull()?.let(::cpuTotalsFromStatLine) ?: return 0
    if (one.first <= 0) return 0
    return (100.0 * (one.first - one.second) / one.first).roundToInt().coerceIn(0, 100)
}

/**
 * Use% from a `df -Pk /` section ([diskSection]: the lines after the `@DISK` marker): the first
 * token of the form `87%`. The `Filesystem … Capacity Mounted on` header has no `%` so it's
 * skipped, leaving the root data line. null if there's no data line.
 */
private fun diskPercentFromDf(diskSection: List<String>): Int? {
    fun percentToken(line: String): Int? =
        line.split(WHITESPACE).firstNotNullOfOrNull { tok ->
            if (tok.endsWith("%")) tok.dropLast(1).toIntOrNull() else null
        }
    return diskSection.firstNotNullOfOrNull { percentToken(it) }
}
