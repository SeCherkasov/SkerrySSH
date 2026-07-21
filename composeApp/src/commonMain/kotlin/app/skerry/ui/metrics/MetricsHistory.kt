package app.skerry.ui.metrics

/**
 * One point of the monitor's rolling window — what the sparklines draw. Percentages are 0..100,
 * network rates are bytes per second derived from the counter delta between two polls.
 */
data class MetricsSample(
    val cpuPercent: Int,
    val memPercent: Int,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
)

/**
 * Samples kept per session — at the default 3 s poll that's about three minutes of history, enough
 * for a spike to stay on screen long enough to notice and cheap to hold per session.
 */
const val METRICS_HISTORY_SIZE = 60

/**
 * Appends [sample] to a history window, dropping the oldest points beyond [capacity]. Returns a new
 * list (the controller swaps it into Compose state, so it must not be mutated in place).
 */
fun List<MetricsSample>.appendCapped(sample: MetricsSample, capacity: Int = METRICS_HISTORY_SIZE): List<MetricsSample> {
    val next = this + sample
    return if (next.size <= capacity) next else next.takeLast(capacity.coerceAtLeast(1))
}

/** Whether a host can serve metrics at all — drives "…" vs. a plain "unavailable" in the panel. */
enum class MetricsAvailability {
    /** Polling has started but nothing usable has arrived yet. */
    Probing,

    /** At least one snapshot parsed — the panel shows live values. */
    Live,

    /** No exec channel (telnet/serial) or output that never parses (non-Linux) — polling stopped. */
    Unsupported,
}
