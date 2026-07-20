package app.skerry.ui.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing of the host-monitor sections (swap, network counters, filesystems, top processes) —
 * everything the info panel draws beyond the three original meters.
 */
class HostMetricsMonitorTest {

    private val fullOutput = """
        cpu  100 0 100 800 0 0 0 0
        cpu  150 0 150 900 0 0 0 0
        @MEM
                      total        used        free      shared  buff/cache   available
        Mem:     4000000000  2100000000  1000000000     1000000   900000000  1700000000
        Swap:    2000000000   500000000  1500000000
        @DISK
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/sda1         51475068  42000000   6900000      87% /
        tmpfs               400000      1000    399000       1% /run
        /dev/sda2        209715200 120000000  78000000      62% /var
        @NET
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets
            lo: 1000000    100    0    0    0     0          0         0  1000000     100
          eth0: 5000000    500    0    0    0     0          0         0  2000000     200
          eth1:  500000     50    0    0    0     0          0         0   100000      10
        @PROC
        1234  12.5  3.4 postgres
         987   4.0  1.2 nginx
        @UPTIME
        372765.42 1488907.15
    """.trimIndent()

    @Test
    fun parses_swap_usage() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(500_000_000L, m.swapUsedBytes)
        assertEquals(2_000_000_000L, m.swapTotalBytes)
    }

    @Test
    fun swap_is_zero_when_line_absent() {
        val m = parseHostMetrics(minimal)!!
        assertEquals(0L, m.swapTotalBytes)
        assertEquals(0L, m.swapUsedBytes)
    }

    @Test
    fun sums_network_counters_across_interfaces_excluding_loopback() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(5_500_000L, m.netRxBytes) // eth0 + eth1, lo excluded
        assertEquals(2_100_000L, m.netTxBytes)
    }

    @Test
    fun network_counters_are_null_when_section_absent() {
        val m = parseHostMetrics(minimal)!!
        assertEquals(null, m.netRxBytes)
        assertEquals(null, m.netTxBytes)
    }

    @Test
    fun lists_real_filesystems_and_skips_pseudo_ones() {
        val disks = parseHostMetrics(fullOutput)!!.disks
        assertEquals(listOf("/", "/var"), disks.map { it.mount }) // tmpfs /run dropped
        val root = disks.first()
        assertEquals(87, root.percent)
        assertEquals(42_000_000L * 1024, root.usedBytes) // df -Pk reports KiB blocks
        assertEquals(51_475_068L * 1024, root.totalBytes)
    }

    @Test
    fun filesystems_parse_from_a_headerless_disk_section() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sda1  100 87 13 87% /
        """.trimIndent()
        assertEquals(listOf("/"), parseHostMetrics(out)!!.disks.map { it.mount })
    }

    @Test
    fun parses_top_processes_in_output_order() {
        val procs = parseHostMetrics(fullOutput)!!.processes
        assertEquals(2, procs.size)
        assertEquals(1234, procs[0].pid)
        assertEquals(12.5f, procs[0].cpuPercent)
        assertEquals(3.4f, procs[0].memPercent)
        assertEquals("postgres", procs[0].command)
        assertEquals("nginx", procs[1].command)
    }

    @Test
    fun process_command_from_the_host_is_length_capped_and_stripped_of_control_chars() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sda1  100 87 13 87% /
            @PROC
            42  1.0  1.0 ${"\u001b[31mevil"}${"X".repeat(200)}
        """.trimIndent()
        val cmd = parseHostMetrics(out)!!.processes.single().command
        assertTrue(cmd.length <= 40, "command must be capped, was ${cmd.length}")
        assertTrue(cmd.none { it.code < 0x20 }, "control characters must be stripped: $cmd")
    }

    @Test
    fun malformed_process_rows_are_skipped_without_failing_the_snapshot() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sda1  100 87 13 87% /
            @PROC
            not-a-pid  x  y
            77  2.0  1.0 sshd
        """.trimIndent()
        val procs = parseHostMetrics(out)!!.processes
        assertEquals(listOf("sshd"), procs.map { it.command })
    }

    @Test
    fun keeps_mount_points_that_contain_spaces() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sdb1  100 87 13 87% /mnt/backup drive
        """.trimIndent()
        assertEquals("/mnt/backup drive", parseHostMetrics(out)!!.disks.single().mount)
    }

    @Test
    fun a_truncated_interface_row_does_not_discard_the_valid_ones() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            /dev/sda1  100 87 13 87% /
            @NET
              eth0: 5000000 500 0 0 0 0 0 0 2000000 200
              eth1: 12345
        """.trimIndent()
        val m = parseHostMetrics(out)!!
        assertEquals(5_000_000L, m.netRxBytes) // the half-written row is skipped, eth0 still counts
        assertEquals(2_000_000L, m.netTxBytes)
    }

    private val minimal = """
        cpu  100 0 100 800 0 0 0 0
        @MEM
        Mem:  4000000000 2100000000 1000000000
        @DISK
        /dev/sda1  100 87 13 87% /
    """.trimIndent()
}
