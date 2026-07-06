package app.skerry.ui.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostMetricsTest {

    private val fullOutput = """
        cpu  100 0 100 800 0 0 0 0
        cpu  150 0 150 900 0 0 0 0
        @MEM
                      total        used        free      shared  buff/cache   available
        Mem:     4000000000  2100000000  1000000000     1000000   900000000  1700000000
        Swap:    2000000000           0  2000000000
        @DISK
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/sda1         51475068  42000000   6900000      87% /
        @UPTIME
        372765.42 1488907.15
        @LOAD
        0.42 0.51 0.48 1/512 28931
        @OS
        PRETTY_NAME="Ubuntu 22.04.4 LTS"
        @KERNEL
        Linux 5.15.0-105-generic x86_64
        @CPU
        4
    """.trimIndent()

    @Test
    fun parses_cpu_by_delta_of_two_proc_stat_samples() {
        val m = parseHostMetrics(fullOutput)!!
        // total 1000→1200 (Δ200), idle 800→900 (Δ100) ⇒ busy 100/200 = 50%
        assertEquals(50, m.cpuPercent)
        assertEquals(0.5f, m.cpuFraction)
    }

    @Test
    fun parses_memory_used_and_total() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(2_100_000_000L, m.memUsedBytes)
        assertEquals(4_000_000_000L, m.memTotalBytes)
        assertEquals(0.525f, m.memFraction, 0.001f)
    }

    @Test
    fun parses_disk_use_percent() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(87, m.diskPercent)
        assertEquals(0.87f, m.diskFraction, 0.001f)
    }

    @Test
    fun single_cpu_sample_falls_back_to_instantaneous() {
        val out = """
            cpu  200 0 200 600 0 0 0 0
            @MEM
            Mem:     4000000000  2000000000  2000000000
            @DISK
            /dev/sda1  100 50 50 10% /
        """.trimIndent()
        // total 1000, idle 600 ⇒ busy 400/1000 = 40%
        assertEquals(40, parseHostMetrics(out)!!.cpuPercent)
    }

    @Test
    fun returns_null_when_memory_section_missing() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @DISK
            /dev/sda1  100 87 13 87% /
        """.trimIndent()
        assertNull(parseHostMetrics(out))
    }

    @Test
    fun returns_null_when_disk_section_missing() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
        """.trimIndent()
        assertNull(parseHostMetrics(out))
    }

    @Test
    fun disk_percent_taken_only_from_disk_section() {
        // A %-token from the neighboring (mem) section must not be picked up as the disk metric.
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            Noise   99% ignored
            @DISK
            Filesystem     1024-blocks      Used Available Capacity Mounted on
            /dev/sda1         51475068  42000000   6900000      87% /
        """.trimIndent()
        assertEquals(87, parseHostMetrics(out)!!.diskPercent)
    }

    @Test
    fun disk_takes_root_row_when_multiple_data_rows_present() {
        // When df has multiple rows, the first data row (after the header) — root — is used.
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            Filesystem     1024-blocks      Used Available Capacity Mounted on
            /dev/sda1         51475068  42000000   6900000      87% /
            /dev/sda2        209715200 120000000  78000000      62% /var
        """.trimIndent()
        assertEquals(87, parseHostMetrics(out)!!.diskPercent)
    }

    @Test
    fun parses_host_facts_from_their_sections() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(372_765L, m.uptimeSeconds) // first token of /proc/uptime, fractional part dropped
        assertEquals("0.42 0.51 0.48", m.loadAverage) // first three tokens of /proc/loadavg
        assertEquals("Ubuntu 22.04.4 LTS", m.osName) // PRETTY_NAME without quotes
        assertEquals("Linux 5.15.0-105-generic x86_64", m.kernel)
        assertEquals(4, m.cpuCount)
    }

    @Test
    fun host_facts_are_null_when_their_sections_absent() {
        // Old format without the new sections: resources parse, facts are null (not garbage).
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sda1  100 87 13 87% /
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertNull(m.uptimeSeconds)
        assertNull(m.loadAverage)
        assertNull(m.osName)
        assertNull(m.kernel)
        assertNull(m.cpuCount)
    }

    @Test
    fun caps_length_of_server_provided_os_and_kernel_strings() {
        val longName = "X".repeat(500)
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sda1  100 87 13 87% /
            @OS
            PRETTY_NAME="$longName"
            @KERNEL
            $longName
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertEquals(120, m.osName?.length) // length capped to the layout limit
        assertEquals(120, m.kernel?.length)
    }

    @Test
    fun formats_uptime_with_days_hours_minutes_seconds() {
        assertEquals("04:12:45", formatUptime(4 * 3600 + 12 * 60 + 45L))
        assertEquals("4d 07:01:05", formatUptime(4 * 86_400 + 7 * 3600 + 1 * 60 + 5L))
        assertEquals("00:00:09", formatUptime(9L))
        assertEquals("00:00:00", formatUptime(-5L)) // negative clamps to zero
    }

    @Test
    fun clamps_fractions_into_unit_range() {
        val m = HostMetrics(cpuPercent = 150, memUsedBytes = 9, memTotalBytes = 4, diskPercent = -5)
        assertEquals(1f, m.cpuFraction)
        assertEquals(1f, m.memFraction)
        assertEquals(0f, m.diskFraction)
        assertTrue(m.cpuFraction in 0f..1f && m.memFraction in 0f..1f && m.diskFraction in 0f..1f)
    }
}
