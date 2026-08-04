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
    fun survives_a_missing_disk_section() {
        // One unreadable section must not throw away the rest of the snapshot: a host whose df
        // output is unusable still reports CPU and memory (and would otherwise be declared unable
        // to serve metrics at all after a few polls).
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertEquals(50, m.cpuPercent)
        assertEquals(0, m.diskPercent)
        assertTrue(m.disks.isEmpty())
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
    fun parses_processes_with_resident_memory() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @PROC
            1841  18.4  12.5 1258291 postgres
            2210   7.1   1.4  188416 nginx
        """.trimIndent()
        val processes = parseHostMetrics(out)!!.processes
        assertEquals(2, processes.size)
        assertEquals(1841, processes[0].pid)
        assertEquals(18.4f, processes[0].cpuPercent, 0.01f)
        // ps reports RSS in KiB — the snapshot carries bytes.
        assertEquals(1_258_291L * 1024, processes[0].rssBytes)
        assertEquals("postgres", processes[0].command)
    }

    @Test
    fun parses_systemd_units_with_their_state() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @SERVICES
            nginx.service loaded active running A high performance web server
            unattended-upgrades.service loaded activating start Unattended Upgrades Shutdown
            fail2ban.service loaded failed failed Fail2Ban Service
        """.trimIndent()
        val services = parseHostMetrics(out)!!.services
        assertEquals(3, services.size)
        assertEquals("nginx.service", services[0].name)
        assertEquals(ServiceState.Active, services[0].state)
        assertEquals("running", services[0].sub)
        assertEquals(ServiceState.Activating, services[1].state)
        assertEquals(ServiceState.Failed, services[2].state)
    }

    @Test
    fun parses_containers_and_joins_their_cpu_share() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @CONTAINERS
            app-web${'\t'}app:0.2.1${'\t'}Up 3 days
            redis${'\t'}redis:7${'\t'}Up 12 days
            @CSTATS
            app-web${'\t'}4.10%
        """.trimIndent()
        val containers = parseHostMetrics(out)!!.containers
        assertEquals(2, containers.size)
        assertEquals("app-web", containers[0].name)
        assertEquals("app:0.2.1", containers[0].image)
        assertEquals("Up 3 days", containers[0].status)
        assertEquals(4.1f, containers[0].cpuPercent!!, 0.01f)
        // No stats row for this one — the column says so instead of claiming an idle container.
        assertNull(containers[1].cpuPercent)
    }

    @Test
    fun services_and_containers_are_empty_when_their_sections_absent() {
        val m = parseHostMetrics(fullOutput)!!
        assertTrue(m.services.isEmpty())
        assertTrue(m.containers.isEmpty())
    }

    @Test
    fun names_from_the_host_are_stripped_of_control_characters_and_capped() {
        val long = "c".repeat(200)
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @SERVICES
            ${'\u001B'}[31mnginx.service loaded active running Web
            @CONTAINERS
            $long${'\t'}$long${'\t'}Up 3 days
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertEquals("[31mnginx.service", m.services[0].name)
        assertTrue(m.containers[0].name.length <= 40)
        assertTrue(m.containers[0].image.length <= 40)
    }

    @Test
    fun load_average_from_the_host_is_sanitized_like_the_other_facts() {
        // The exec answer is whatever the host chooses to send, not necessarily /proc/loadavg:
        // escape sequences and unbounded length must not reach the tiles or the alert feed.
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @LOAD
            ${''}[2J0.42 ${"9".repeat(300)} 0.48
        """.trimIndent()
        val load = parseHostMetrics(out)!!.loadAverage!!
        assertTrue(load.none { it.code < 0x20 }, "control characters must be stripped: $load")
        assertTrue(load.length <= 120, "load average must be length capped, was ${load.length}")
    }

    @Test
    fun invisible_and_direction_changing_characters_are_stripped_from_host_names() {
        // A right-to-left override in a process or container name reverses what the row reads as —
        // a plain spoof of which process is really busy.
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @PROC
            42  1.0  1.0 4096 ssh${'‮'}d${'​'}
            @CONTAINERS
            web${'‮'}app${'\t'}img${'﻿'}:1${'\t'}Up 3 days
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertEquals("sshd", m.processes.single().command)
        assertEquals("webapp", m.containers.single().name)
        assertEquals("img:1", m.containers.single().image)
    }

    @Test
    fun the_row_count_of_every_list_is_capped_on_our_side() {
        // The host is asked for 8 rows; it is free to answer with a thousand. The screen draws
        // every row it is given, so the cap belongs here too, not only in the shell command.
        val procs = (1..40).joinToString("\n") { "$it  1.0  1.0 4096 proc$it" }
        val disks = (1..40).joinToString("\n") { "/dev/sd$it  100 50 50 50% /mnt/$it" }
        val units = (1..40).joinToString("\n") { "unit$it.service loaded active running Unit $it" }
        val containers = (1..40).joinToString("\n") { "box$it${'\t'}img:$it${'\t'}Up 3 days" }
        val stats = (1..40).joinToString("\n") { "box$it${'\t'}1.0%" }
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            $disks
            @PROC
            $procs
            @SERVICES
            $units
            @CONTAINERS
            $containers
            @CSTATS
            $stats
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertTrue(m.processes.size <= 8, "processes must be capped, was ${m.processes.size}")
        assertTrue(m.disks.size <= 8, "filesystems must be capped, was ${m.disks.size}")
        assertTrue(m.services.size <= 8, "units must be capped, was ${m.services.size}")
        assertTrue(m.containers.size <= 8, "containers must be capped, was ${m.containers.size}")
    }

    @Test
    fun picks_the_busiest_interface_as_the_primary_one() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @NET
            Inter-|   Receive                                                |  Transmit
                lo: 900000000 10 0 0 0 0 0 0 900000000 10 0 0 0 0 0 0
              ens3: 500000000 10 0 0 0 0 0 0 100000000 10 0 0 0 0 0 0
              eth1:      1000 10 0 0 0 0 0 0      1000 10 0 0 0 0 0 0
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        // Loopback is excluded from both the counters and the name.
        assertEquals("ens3", m.netInterface)
        assertEquals(500_001_000L, m.netRxBytes)
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
