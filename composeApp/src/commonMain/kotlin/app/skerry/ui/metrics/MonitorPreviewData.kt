package app.skerry.ui.metrics

// Fixed monitor snapshot for the paths that have no live session: the desktop info panel's preview
// mode and the offscreen screenshot renders. Deliberately static — offscreen images must come out
// identical between runs.

internal val PREVIEW_HOST_METRICS = HostMetrics(
    cpuPercent = 34,
    memUsedBytes = 2_100_000_000,
    memTotalBytes = 4_000_000_000,
    diskPercent = 87,
    uptimeSeconds = 372_765,
    loadAverage = "0.42 0.51 0.48",
    osName = "Ubuntu 22.04.4 LTS",
    kernel = "Linux 5.15.0-105-generic x86_64",
    cpuCount = 4,
    swapUsedBytes = 210_000_000,
    swapTotalBytes = 2_000_000_000,
    netRxBytes = 1,
    netTxBytes = 1,
    disks = listOf(
        DiskUsage("/", 42_000_000_000, 49_000_000_000, 87),
        DiskUsage("/var", 120_000_000_000, 210_000_000_000, 62),
    ),
    processes = listOf(
        ProcessSample(2481, 12.4f, 8.1f, "postgres"),
        ProcessSample(991, 4.2f, 2.3f, "nginx"),
        ProcessSample(1204, 1.8f, 5.7f, "node"),
    ),
)

internal const val PREVIEW_RX_RATE = 384_000L
internal const val PREVIEW_TX_RATE = 96_000L

/** A calm, repeating history so the sparklines have shape without looking like random noise. */
internal val PREVIEW_METRICS_HISTORY = List(40) { i ->
    MetricsSample(
        cpuPercent = 28 + (i * 7) % 24,
        memPercent = 50 + (i * 3) % 8,
        rxBytesPerSec = 120_000L + (i * 37_000L) % 400_000,
        txBytesPerSec = 40_000L + (i * 11_000L) % 90_000,
    )
}
