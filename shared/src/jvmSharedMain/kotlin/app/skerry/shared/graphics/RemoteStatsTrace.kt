package app.skerry.shared.graphics

import java.util.concurrent.ConcurrentHashMap

/**
 * The diagnostics counters as a trace line, for a session running where no overlay can be opened
 * (headless reproduction, a bug report). Off unless `-Dskerry.remote.statsTrace=1`, same switch
 * style as `h264Trace`/`audioTrace`, and rate-limited per label so the read loop can call it per
 * message without flooding stderr.
 */
private val statsTraceEnabled: Boolean = System.getProperty("skerry.remote.statsTrace") == "1"

private val lastTraceNanos = ConcurrentHashMap<String, Long>()

private const val TRACE_INTERVAL_NANOS = 5_000_000_000L

fun remoteStatsTrace(label: String, diagnostics: RemoteDesktopDiagnostics) {
    if (!statsTraceEnabled) return
    val now = System.nanoTime()
    val last = lastTraceNanos[label]
    if (last != null && now - last < TRACE_INTERVAL_NANOS) return
    lastTraceNanos[label] = now
    val s = diagnostics.snapshot()
    System.err.println(
        "remote stats [$label]: paths=${s.paths.joinToString("+")} codec=${s.lastCodec} " +
            "negotiated=${s.negotiated} frames=${s.serverFrames} " +
            "dropped=${s.droppedOrders}/${s.droppedRects} repaints=${s.fullRepaints} " +
            "decode=${s.decodeNanos / 1_000_000}ms/${s.decodeCount} in=${s.bytesIn} out=${s.bytesOut}",
    )
}
