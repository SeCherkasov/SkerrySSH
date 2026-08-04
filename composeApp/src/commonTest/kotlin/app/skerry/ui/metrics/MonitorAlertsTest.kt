package app.skerry.ui.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alert feed: which thresholds raise an entry, when an entry clears, and how its age reads.
 * Everything here is derived from the snapshots the poller already fetches — the host is never
 * asked anything extra for it.
 */
class MonitorAlertsTest {

    private fun metrics(
        cpu: Int = 10,
        memUsed: Long = 1_000_000_000,
        memTotal: Long = 4_000_000_000,
        swapUsed: Long = 0,
        swapTotal: Long = 0,
        disks: List<DiskUsage> = emptyList(),
        load: String? = null,
        cpuCount: Int? = 4,
    ) = HostMetrics(
        cpuPercent = cpu,
        memUsedBytes = memUsed,
        memTotalBytes = memTotal,
        diskPercent = disks.firstOrNull { it.mount == "/" }?.percent ?: 0,
        swapUsedBytes = swapUsed,
        swapTotalBytes = swapTotal,
        disks = disks,
        loadAverage = load,
        cpuCount = cpuCount,
    )

    @Test
    fun a_full_filesystem_raises_one_alert_per_mount() {
        val conditions = alertConditions(
            metrics(disks = listOf(DiskUsage("/", 9, 10, 87), DiskUsage("/var", 1, 10, 12))),
        )
        assertEquals(listOf(AlertCondition(AlertKind.DiskFull, "/")), conditions)
    }

    @Test
    fun swap_in_heavy_use_and_load_above_the_core_count_raise_alerts() {
        val conditions = alertConditions(
            metrics(swapUsed = 1_600_000_000, swapTotal = 2_000_000_000, load = "5.10 3.20 2.00", cpuCount = 4),
        )
        assertEquals(
            setOf(AlertCondition(AlertKind.SwapHeavy, "80"), AlertCondition(AlertKind.LoadHigh, "5.10")),
            conditions.toSet(),
        )
    }

    @Test
    fun a_quiet_host_raises_nothing() {
        assertTrue(alertConditions(metrics(disks = listOf(DiskUsage("/", 1, 10, 12)), load = "0.42 0.51 0.48")).isEmpty())
    }

    @Test
    fun memory_above_the_threshold_raises_an_alert() {
        val conditions = alertConditions(metrics(memUsed = 3_800_000_000, memTotal = 4_000_000_000))
        assertEquals(listOf(AlertCondition(AlertKind.MemoryHigh, "95")), conditions)
    }

    @Test
    fun the_log_records_a_raise_once_and_a_clear_when_it_goes_away() {
        val log = HostAlertLog()
        val full = metrics(disks = listOf(DiskUsage("/", 9, 10, 87)))
        log.update(full, nowMillis = 1_000)
        log.update(full, nowMillis = 4_000) // still full — nothing new to say
        assertEquals(1, log.entries.size)
        assertEquals(AlertKind.DiskFull, log.entries.single().kind)
        assertTrue(log.entries.single().active)

        log.update(metrics(disks = listOf(DiskUsage("/", 1, 10, 40))), nowMillis = 7_000)

        // Newest first: the recovery, then the original alert it resolves.
        assertEquals(2, log.entries.size)
        assertEquals(7_000, log.entries.first().atMillis)
        assertTrue(!log.entries.first().active, "the newest entry is the recovery")
        assertEquals(AlertKind.DiskFull, log.entries.first().kind)
    }

    @Test
    fun a_value_hovering_at_the_threshold_does_not_flood_the_log() {
        val log = HostAlertLog()
        log.update(metrics(disks = listOf(DiskUsage("/", 9, 10, 86))), nowMillis = 1_000)
        // Back to 85 and up to 86 again: within the hysteresis band, so the alert neither clears
        // nor re-raises — a poll every few seconds would otherwise fill the card with noise.
        log.update(metrics(disks = listOf(DiskUsage("/", 9, 10, 85))), nowMillis = 2_000)
        log.update(metrics(disks = listOf(DiskUsage("/", 9, 10, 86))), nowMillis = 3_000)

        assertEquals(1, log.entries.size)
    }

    @Test
    fun a_rising_value_above_the_line_is_one_alert_not_one_per_poll() {
        val log = HostAlertLog()
        // Memory names its own reading as the subject, and that reading moves on every poll while
        // the condition itself never clears — the feed must still hold a single entry.
        log.update(metrics(memUsed = 3_640_000_000, memTotal = 4_000_000_000), nowMillis = 1_000) // 91 %
        log.update(metrics(memUsed = 3_720_000_000, memTotal = 4_000_000_000), nowMillis = 2_000) // 93 %
        log.update(metrics(memUsed = 3_800_000_000, memTotal = 4_000_000_000), nowMillis = 3_000) // 95 %

        assertEquals(1, log.entries.size)
        assertEquals(AlertKind.MemoryHigh, log.entries.single().kind)
        assertTrue(log.entries.single().active)
    }

    @Test
    fun two_filesystems_over_the_line_are_two_alerts() {
        val log = HostAlertLog()
        // Disk alerts are per mount, so the subject does separate them — the fix for the reading
        // above must not collapse these two into one.
        log.update(
            metrics(disks = listOf(DiskUsage("/", 9, 10, 90), DiskUsage("/var", 9, 10, 92))),
            nowMillis = 1_000,
        )
        assertEquals(setOf("/", "/var"), log.entries.map { it.subject }.toSet())
    }

    @Test
    fun conditions_crossing_in_the_same_poll_are_all_recorded() {
        val log = HostAlertLog()
        log.update(
            metrics(
                memUsed = 3_800_000_000,
                memTotal = 4_000_000_000,
                swapUsed = 1_600_000_000,
                swapTotal = 2_000_000_000,
                disks = listOf(DiskUsage("/", 9, 10, 90)),
                load = "9.00 3.20 2.00",
            ),
            nowMillis = 1_000,
        )
        assertEquals(
            setOf(AlertKind.DiskFull, AlertKind.MemoryHigh, AlertKind.SwapHeavy, AlertKind.LoadHigh),
            log.entries.map { it.kind }.toSet(),
        )
    }

    @Test
    fun the_log_keeps_only_the_most_recent_entries() {
        val log = HostAlertLog()
        var now = 0L
        repeat(HOST_ALERT_LOG_SIZE + 3) { i ->
            val percent = if (i % 2 == 0) 90 else 40
            log.update(metrics(disks = listOf(DiskUsage("/", 9, 10, percent))), nowMillis = now)
            now += 1_000
        }
        assertEquals(HOST_ALERT_LOG_SIZE, log.entries.size)
        assertEquals(now - 1_000, log.entries.first().atMillis) // newest first
    }

    @Test
    fun age_reads_as_now_minutes_hours_or_a_day() {
        assertEquals(AlertAge.Now, alertAge(30_000))
        assertEquals(AlertAge.Minutes(5), alertAge(5 * 60_000L + 4_000))
        assertEquals(AlertAge.Hours(2), alertAge(2 * 3_600_000L))
        assertEquals(AlertAge.Yesterday, alertAge(30 * 3_600_000L))
        assertEquals(AlertAge.Days(3), alertAge(3 * 86_400_000L))
        assertEquals(AlertAge.Now, alertAge(-5_000)) // clock skew reads as "now", not a negative age
    }
}
