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
    val swapUsedBytes: Long = 0,
    val swapTotalBytes: Long = 0,
    // Cumulative interface counters since boot (loopback excluded); null when /proc/net/dev is
    // unavailable. Rates are derived from the delta between polls by HostMetricsController.
    val netRxBytes: Long? = null,
    val netTxBytes: Long? = null,
    /** Interface that carries most of the traffic — what the network card names. */
    val netInterface: String? = null,
    val disks: List<DiskUsage> = emptyList(),
    val processes: List<ProcessSample> = emptyList(),
    // Only in a full poll (see HostMetricsController): empty on a host without systemd/containers,
    // and on the cheap polls in between, where the controller carries the previous lists over.
    val services: List<ServiceUnit> = emptyList(),
    val containers: List<ContainerSample> = emptyList(),
) {
    val cpuFraction: Float get() = (cpuPercent / 100f).coerceIn(0f, 1f)
    val memFraction: Float get() = if (memTotalBytes > 0) (memUsedBytes.toFloat() / memTotalBytes).coerceIn(0f, 1f) else 0f
    val diskFraction: Float get() = (diskPercent / 100f).coerceIn(0f, 1f)
    val swapFraction: Float get() = if (swapTotalBytes > 0) (swapUsedBytes.toFloat() / swapTotalBytes).coerceIn(0f, 1f) else 0f
}

/** One mounted filesystem from `df -Pk` (pseudo filesystems are filtered out while parsing). */
data class DiskUsage(val mount: String, val usedBytes: Long, val totalBytes: Long, val percent: Int) {
    val fraction: Float get() = (percent / 100f).coerceIn(0f, 1f)
}

/**
 * One row of the remote `ps` top list. [command] is sanitized host output — safe to draw as is;
 * [rssBytes] is the resident set size (`ps` reports it in KiB, this is bytes).
 */
data class ProcessSample(
    val pid: Int,
    val cpuPercent: Float,
    val memPercent: Float,
    val rssBytes: Long,
    val command: String,
)

/**
 * What `systemctl` says about a unit. Only the states worth showing are collected (see
 * [HostMetricsController.FULL_METRICS_COMMAND]); [Other] is the fallback for a word we don't know,
 * which is drawn plainly rather than coloured as good or bad news.
 */
enum class ServiceState { Active, Activating, Failed, Other }

/** One systemd unit: its name, its ACTIVE column, and the SUB column ("running", "start", "failed"). */
data class ServiceUnit(val name: String, val state: ServiceState, val sub: String)

/**
 * One container from `docker ps` (or `podman ps`). [cpuPercent] comes from a separate `stats` call
 * and is `null` when that call found nothing for this container — the column then says so instead
 * of claiming an idle container.
 */
data class ContainerSample(val name: String, val image: String, val status: String, val cpuPercent: Float?)

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
 * <df -Pk: data lines with a Use% column and a mount point>
 * ```
 *
 * A full poll adds `@SERVICES` (systemd units), `@CONTAINERS` and `@CSTATS` (tab-separated docker
 * rows); a cheap poll leaves them out and the corresponding lists come back empty.
 *
 * CPU is computed from the delta of two /proc/stat samples (non-idle fraction over the interval);
 * with a single sample, it's instantaneous since system start. Returns `null` if memory
 * is missing from the output (e.g. a non-Linux server) — the UI then shows "no data" instead of
 * garbage. A missing/unreadable disk section only empties the filesystem list.
 */
fun parseHostMetrics(raw: String): HostMetrics? {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    // Each metric is looked up strictly within its own section (between its marker and the next
    // marker), so a stray %-token from a neighboring section (or another df mount line) doesn't
    // get picked up as the metric. CPU is the `cpu …` /proc/stat lines, always before @MEM.
    val cpuPercent = cpuPercentFromStat(lines.filter { it.startsWith("cpu ") })

    // Memory is the one required section: its absence signals non-Linux/truncated output -> null overall.
    val memSection = sectionOrAll(lines, "@MEM")
    val memLine = memSection.firstOrNull { it.startsWith("Mem:") } ?: return null
    val memTokens = memLine.split(WHITESPACE)
    val memTotal = memTokens.getOrNull(1)?.toLongOrNull() ?: return null
    val memUsed = memTokens.getOrNull(2)?.toLongOrNull() ?: return null

    val swapLine = memSection.firstOrNull { it.startsWith("Swap:") }?.split(WHITESPACE)
    val swapTotal = swapLine?.getOrNull(1)?.toLongOrNull() ?: 0L
    val swapUsed = swapLine?.getOrNull(2)?.toLongOrNull() ?: 0L

    val diskSection = sectionOrAll(lines, "@DISK")
    val disks = parseDisks(diskSection)
    // Root usage keeps its own lookup: the meter must show `/` even if it isn't the first row.
    // Unlike memory, an unreadable disk section doesn't fail the snapshot — CPU, memory, network
    // and processes are still worth showing, and failing here would eventually declare the whole
    // host unable to serve metrics over one section.
    val diskPercent = disks.firstOrNull { it.mount == "/" }?.percent
        ?: diskPercentFromDf(diskSection) ?: 0

    // Host facts are optional: their sections may be absent (old server) — the field stays null then.
    val uptimeSeconds = section(lines, "@UPTIME").firstOrNull()
        ?.split(WHITESPACE)?.firstOrNull()?.toDoubleOrNull()?.toLong()
    val loadAverage = section(lines, "@LOAD").firstOrNull()
        ?.split(WHITESPACE)?.take(3)?.takeIf { it.size == 3 }?.joinToString(" ")
        // Same treatment as OS/kernel: the exec answer is whatever the host chose to send, and this
        // string reaches the CPU tile, the System card and the alert feed.
        ?.let { hostText(it, FACT_MAX_LEN) }
    // OS/kernel come from the remote (trusted but potentially misbehaving) host — cap the length
    // so an anomalously long string can't break the info panel layout (defence-in-depth).
    val osName = section(lines, "@OS").firstOrNull { it.startsWith("PRETTY_NAME=") }
        ?.removePrefix("PRETTY_NAME=")?.trim('"')?.take(FACT_MAX_LEN)?.takeIf { it.isNotBlank() }
    val kernel = section(lines, "@KERNEL").firstOrNull()?.take(FACT_MAX_LEN)?.takeIf { it.isNotBlank() }
    val cpuCount = section(lines, "@CPU").firstOrNull()?.toIntOrNull()
    val net = parseNetCounters(section(lines, "@NET"))
    val processes = parseProcesses(section(lines, "@PROC"))
    val services = parseServices(section(lines, "@SERVICES"))
    val containers = parseContainers(section(lines, "@CONTAINERS"), parseContainerCpu(section(lines, "@CSTATS")))

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
        swapUsedBytes = swapUsed,
        swapTotalBytes = swapTotal,
        netRxBytes = net?.rxBytes,
        netTxBytes = net?.txBytes,
        netInterface = net?.busiest,
        disks = disks,
        processes = processes,
        services = services,
        containers = containers,
    )
}

private val WHITESPACE = Regex("\\s+")

/** Max length of string host facts (OS/kernel) from the remote server — protects the info panel layout. */
private const val FACT_MAX_LEN = 120

/** All output section markers — [section] uses these to find the boundary of "its" section. */
private val SECTION_MARKERS = setOf(
    "@MEM", "@DISK", "@NET", "@PROC", "@UPTIME", "@LOAD", "@OS", "@KERNEL", "@CPU",
    "@SERVICES", "@CONTAINERS", "@CSTATS",
)

/** Max length of a mount path / process command line drawn in the monitor list. */
private const val MOUNT_MAX_LEN = 40
private const val COMMAND_MAX_LEN = 40

/** Max length of a unit / container / image name drawn in the monitor cards. */
private const val NAME_MAX_LEN = 40

/**
 * How many rows a single card is willing to list. The shell command asks the host for this many,
 * but the answer is the host's to write: the cap belongs on this side too, or a hostile host draws
 * a thousand rows into a non-lazy column.
 */
private const val LIST_MAX_ROWS = 8

/**
 * A string coming from the remote host, made safe to draw: control characters (escape sequences
 * that would repaint the screen) removed and the length capped so one anomalous line can't stretch
 * a card. Blank input yields null — the caller drops the row rather than drawing an empty one.
 */
private fun hostText(raw: String, max: Int): String? =
    raw.filter { it.code >= 0x20 && it.code !in INVISIBLE_CODES && it.code !in BIDI_CODES }
        .take(max)
        .takeIf { it.isNotBlank() }

/** DEL and the C1 control block — invisible, and a terminal would act on some of them. */
private val INVISIBLE_CODES = (0x7F..0x9F) + listOf(0x200B, 0x200C, 0x200D, 0xFEFF)

/**
 * Bidirectional and other Unicode format controls. A process or container named with a
 * RIGHT-TO-LEFT OVERRIDE draws its own row backwards, which is how a busy process passes for an
 * idle one at a glance.
 */
private val BIDI_CODES = (0x200E..0x200F) + (0x202A..0x202E) + (0x2060..0x2069)

/**
 * Kernel-backed filesystems `df` reports next to the real ones. They're either RAM (tmpfs), a
 * read-only image (squashfs, from snaps), or a container layer — listing them as "disks" is noise,
 * and a 100 %-full squashfs would raise a false alarm.
 */
private val PSEUDO_FILESYSTEMS = setOf("tmpfs", "devtmpfs", "squashfs", "overlay", "efivarfs", "udev", "none")

/**
 * Rows of a `df -Pk` section into [DiskUsage] (KiB blocks → bytes). Skips the header (its block
 * column isn't a number) and [PSEUDO_FILESYSTEMS]. The mount is the tail of the row, so paths with
 * spaces survive.
 */
private fun parseDisks(diskSection: List<String>): List<DiskUsage> = diskSection.mapNotNull { line ->
    val t = line.split(WHITESPACE)
    if (t.size < 6) return@mapNotNull null
    val totalKb = t[1].toLongOrNull() ?: return@mapNotNull null
    val usedKb = t[2].toLongOrNull() ?: return@mapNotNull null
    val percent = t[4].takeIf { it.endsWith("%") }?.dropLast(1)?.toIntOrNull() ?: return@mapNotNull null
    val mount = hostText(t.drop(5).joinToString(" "), MOUNT_MAX_LEN) ?: return@mapNotNull null
    if (t[0] in PSEUDO_FILESYSTEMS || mount.startsWith("/snap")) return@mapNotNull null
    DiskUsage(mount, usedKb * 1024, totalKb * 1024, percent.coerceIn(0, 100))
}.take(LIST_MAX_ROWS)

/** Host-wide network counters plus the interface that carries most of the received traffic. */
private data class NetCounters(val rxBytes: Long, val txBytes: Long, val busiest: String?)

/**
 * Cumulative receive/transmit bytes summed over the `/proc/net/dev` interfaces, loopback excluded
 * (it's local traffic and would double-count). Returns null if the section holds no interface rows
 * — the two header lines have no `iface:` prefix and are skipped. The busiest interface by received
 * bytes names the network card, the way `ens3` does in the mockup.
 */
private fun parseNetCounters(netSection: List<String>): NetCounters? {
    var rx = 0L
    var tx = 0L
    var seen = false
    var busiest: String? = null
    var busiestRx = -1L
    for (line in netSection) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.take(colon).trim()
        if (name == "lo") continue
        val values = line.substring(colon + 1).split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        if (values.size < 9) continue
        rx += values[0]
        tx += values[8] // /proc/net/dev: 8 receive columns, then transmit bytes
        seen = true
        if (values[0] > busiestRx) {
            busiestRx = values[0]
            busiest = hostText(name, NAME_MAX_LEN)
        }
    }
    return if (seen) NetCounters(rx, tx, busiest) else null
}

/**
 * Rows of `ps -eo pid=,pcpu=,pmem=,rss=,comm=` into [ProcessSample], in the order the host sorted
 * them. Unparsable rows are dropped rather than failing the snapshot. The command comes from the
 * remote host, so it goes through [hostText].
 */
private fun parseProcesses(procSection: List<String>): List<ProcessSample> = procSection.take(LIST_MAX_ROWS).mapNotNull { line ->
    val t = line.split(WHITESPACE)
    if (t.size < 5) return@mapNotNull null
    val pid = t[0].toIntOrNull() ?: return@mapNotNull null
    val cpu = t[1].toFloatOrNull() ?: return@mapNotNull null
    val mem = t[2].toFloatOrNull() ?: return@mapNotNull null
    val rssKb = t[3].toLongOrNull() ?: return@mapNotNull null
    val command = hostText(t.drop(4).joinToString(" "), COMMAND_MAX_LEN) ?: return@mapNotNull null
    ProcessSample(pid, cpu, mem, rssKb * 1024, command)
}

/**
 * Rows of `systemctl list-units --plain --no-legend` (UNIT LOAD ACTIVE SUB DESCRIPTION) into
 * [ServiceUnit]. A host without systemd answers with an error on stderr and no rows here, so the
 * card simply isn't drawn.
 */
private fun parseServices(section: List<String>): List<ServiceUnit> = section.take(LIST_MAX_ROWS).mapNotNull { line ->
    val t = line.split(WHITESPACE)
    if (t.size < 4) return@mapNotNull null
    val name = hostText(t[0], NAME_MAX_LEN) ?: return@mapNotNull null
    val state = when (t[2]) {
        "active" -> ServiceState.Active
        "activating", "reloading", "deactivating" -> ServiceState.Activating
        "failed" -> ServiceState.Failed
        else -> ServiceState.Other
    }
    ServiceUnit(name, state, hostText(t[3], NAME_MAX_LEN).orEmpty())
}

/**
 * Tab-separated `docker ps` rows (name, image, status) into [ContainerSample], with the CPU share
 * from [cpuByName] (a separate `docker stats` call) joined in by container name.
 */
private fun parseContainers(section: List<String>, cpuByName: Map<String, Float>): List<ContainerSample> =
    section.take(LIST_MAX_ROWS).mapNotNull { line ->
        val t = line.split('\t')
        if (t.size < 3) return@mapNotNull null
        val name = hostText(t[0].trim(), NAME_MAX_LEN) ?: return@mapNotNull null
        val image = hostText(t[1].trim(), NAME_MAX_LEN).orEmpty()
        val status = hostText(t[2].trim(), NAME_MAX_LEN).orEmpty()
        ContainerSample(name, image, status, cpuByName[name])
    }

/** Tab-separated `docker stats --no-stream` rows (name, "4.10%") into a name → percent map. */
private fun parseContainerCpu(section: List<String>): Map<String, Float> = section.mapNotNull { line ->
    val t = line.split('\t')
    if (t.size < 2) return@mapNotNull null
    val name = hostText(t[0].trim(), NAME_MAX_LEN) ?: return@mapNotNull null
    val percent = t[1].trim().removeSuffix("%").toFloatOrNull() ?: return@mapNotNull null
    name to percent
}.toMap()

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
    // Clamped like the per-mount rows: the value comes from the remote host and a malformed
    // "150%" must not drive a meter past full.
    return diskSection.firstNotNullOfOrNull { percentToken(it) }?.coerceIn(0, 100)
}
