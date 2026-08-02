package app.skerry.ui.tunnel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Aggregate throughput of every active tunnel at one poll tick, in bytes per second. */
data class ThroughputSample(val up: Long, val down: Long)

/**
 * One-word cause of a tunnel failure, for the recent-events card. Typed at the point the exception
 * is caught rather than derived from [friendlyTunnelError]'s text, which is localized and written
 * for a human.
 */
enum class TunnelFailureKind { HostKey, Auth, Forward, Connection, Unavailable }

/** What happened to a tunnel, as the events card reports it. */
sealed interface TunnelEventKind {
    data class Failed(val kind: TunnelFailureKind) : TunnelEventKind
    data object Recovered : TunnelEventKind
}

/**
 * A tunnel changing between working and not. [tick] is the telemetry poll counter at the moment it
 * happened — the age shown in the card is derived from it, so the section needs no wall clock.
 */
data class TunnelEvent(
    val tunnelId: String,
    val label: String,
    val port: Int,
    val kind: TunnelEventKind,
    val tick: Long,
)

/**
 * Section-wide telemetry behind the tunnel dashboard: a bounded window of aggregate throughput for
 * the sparkline, and a bounded journal of failures and recoveries. Kept apart from [TunnelManager]
 * — the manager answers for connections, this only records what happened to them, and being a plain
 * object it is directly testable.
 *
 * Failures are deduplicated: a tunnel is either in the failing set or not, so a retry loop against
 * a host that is down writes one entry, not one per attempt.
 */
@Stable
class TunnelTelemetry(private val pollIntervalMillis: Long) {

    /** Oldest first — the sparkline draws left to right. */
    var history: List<ThroughputSample> by mutableStateOf(emptyList())
        private set

    /** Newest first — the card shows the most recent failures. */
    var events: List<TunnelEvent> by mutableStateOf(emptyList())
        private set

    // Snapshot-backed: the events card renders the age of its newest entry, and `events` alone
    // stops changing the moment a failure is recorded. A plain field would freeze the card at
    // "0 s ago" forever, because nothing it observes would ever invalidate again.
    private var tick: Long by mutableStateOf(0)

    // Cause per failing tunnel, not a bare set: a retry that fails for a *different* reason is news,
    // and deduplicating on the id alone would leave the card asserting a cause that no longer holds.
    private val failing = mutableMapOf<String, TunnelFailureKind>()

    /** Records one poll tick of aggregate throughput and advances the clock the events age against. */
    fun sample(up: Long, down: Long) {
        tick++
        history = (history + ThroughputSample(up, down)).takeLast(HISTORY_CAPACITY)
    }

    /** A tunnel stopped working. A repeat of the cause already recorded for it is ignored. */
    fun failed(tunnelId: String, label: String, port: Int, kind: TunnelFailureKind) {
        if (failing.put(tunnelId, kind) == kind) return
        record(TunnelEvent(tunnelId, label, port, TunnelEventKind.Failed(kind), tick))
    }

    /** A tunnel came up. Only newsworthy if it was previously failing. */
    fun active(tunnelId: String, label: String, port: Int) {
        failing.remove(tunnelId) ?: return
        record(TunnelEvent(tunnelId, label, port, TunnelEventKind.Recovered, tick))
    }

    /** Drops a deleted tunnel from the failing set so a recreated one reports its failure again. */
    fun forget(tunnelId: String) {
        failing.remove(tunnelId)
    }

    /** Whole seconds between [event] and the latest poll. */
    fun secondsSince(event: TunnelEvent): Long = (tick - event.tick) * pollIntervalMillis / 1000

    /** Wipes the window and the journal (vault lock): they describe connections that no longer exist. */
    fun reset() {
        history = emptyList()
        events = emptyList()
        failing.clear()
    }

    private fun record(event: TunnelEvent) {
        events = (listOf(event) + events).take(EVENT_CAPACITY)
    }

    companion object {
        /** One minute of one-second samples — the width the sparkline can actually resolve. */
        const val HISTORY_CAPACITY = 60

        /** As many rows as the card has room for. */
        const val EVENT_CAPACITY = 4
    }
}
