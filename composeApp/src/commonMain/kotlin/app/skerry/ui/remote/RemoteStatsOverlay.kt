package app.skerry.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.graphics.RemoteDesktopDiagnostics
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_stats_bridge
import app.skerry.ui.generated.resources.rd_stats_codec
import app.skerry.ui.generated.resources.rd_stats_decode
import app.skerry.ui.generated.resources.rd_stats_draw
import app.skerry.ui.generated.resources.rd_stats_dropped
import app.skerry.ui.generated.resources.rd_stats_in
import app.skerry.ui.generated.resources.rd_stats_negotiated
import app.skerry.ui.generated.resources.rd_stats_out
import app.skerry.ui.generated.resources.rd_stats_path
import app.skerry.ui.generated.resources.rd_stats_redraw_fps
import app.skerry.ui.generated.resources.rd_stats_repaints
import app.skerry.ui.generated.resources.rd_stats_server_fps
import app.skerry.ui.theme.Skerry
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Render-side counters of one session: how long the pixel bridge and the draw take. The protocol
 * half of the picture lives in [RemoteDesktopDiagnostics]; these two are measured where the UI does
 * the work, and are read by the same overlay poll.
 */
class RemoteRenderStats {

    /** Time spent copying decoded pixels into the platform bitmap (the pixel bridge, F-01). */
    @Volatile
    var bridgeNanos: Long = 0
        private set

    @Volatile
    var bridgeCount: Long = 0
        private set

    /** Time spent inside the framebuffer draw pass (includes the bitmap upload on desktop). */
    @Volatile
    var drawNanos: Long = 0
        private set

    @Volatile
    var drawCount: Long = 0
        private set

    fun bridgeTime(nanos: Long) {
        bridgeNanos += nanos
        bridgeCount++
    }

    fun drawTime(nanos: Long) {
        drawNanos += nanos
        drawCount++
    }
}

/** Everything the overlay reads at one poll: the session's counters plus the render-side ones. */
internal data class RemoteStatsSample(
    val diagnostics: RemoteDesktopDiagnostics.Snapshot,
    val redraws: Int,
    val drawNanos: Long,
    val drawCount: Long,
    val bridgeNanos: Long,
    val bridgeCount: Long,
)

/** The overlay's row values, already formatted; every rate is a delta between the two samples. */
internal data class RemoteStatsValues(
    val path: String,
    val codec: String,
    val negotiated: String?,
    val serverFps: String,
    val redrawFps: String,
    val decodeMs: String,
    val bridgeMs: String,
    val drawMs: String,
    val dropped: String,
    val repaints: String,
    val rateIn: String,
    val rateOut: String,
)

internal fun remoteStatsValues(
    previous: RemoteStatsSample,
    now: RemoteStatsSample,
    elapsedMillis: Long,
): RemoteStatsValues {
    val seconds = elapsedMillis.coerceAtLeast(1) / 1000.0
    return RemoteStatsValues(
        path = now.diagnostics.paths.joinToString(" + ").ifEmpty { ABSENT },
        codec = now.diagnostics.lastCodec ?: ABSENT,
        negotiated = now.diagnostics.negotiated,
        serverFps = fmt1((now.diagnostics.serverFrames - previous.diagnostics.serverFrames) / seconds),
        redrawFps = fmt1((now.redraws - previous.redraws) / seconds),
        decodeMs = averageMs(
            now.diagnostics.decodeNanos - previous.diagnostics.decodeNanos,
            now.diagnostics.decodeCount - previous.diagnostics.decodeCount,
        ),
        bridgeMs = averageMs(now.bridgeNanos - previous.bridgeNanos, now.bridgeCount - previous.bridgeCount),
        drawMs = averageMs(now.drawNanos - previous.drawNanos, now.drawCount - previous.drawCount),
        dropped = "${now.diagnostics.droppedOrders} / ${now.diagnostics.droppedRects}",
        repaints = now.diagnostics.fullRepaints.toString(),
        rateIn = byteRate(now.diagnostics.bytesIn - previous.diagnostics.bytesIn, seconds),
        rateOut = byteRate(now.diagnostics.bytesOut - previous.diagnostics.bytesOut, seconds),
    )
}

private const val ABSENT = "—"

/** Average milliseconds over the interval, or absent when nothing was timed in it. */
private fun averageMs(nanos: Long, count: Long): String =
    if (count <= 0) ABSENT else fmt1(nanos / count / 1_000_000.0)

private fun byteRate(bytes: Long, seconds: Double): String {
    val perSecond = bytes / seconds
    return when {
        perSecond >= 1 shl 20 -> "${fmt1(perSecond / (1 shl 20))} MB/s"
        perSecond >= 1 shl 10 -> "${fmt1(perSecond / (1 shl 10))} KB/s"
        else -> "${perSecond.roundToInt()} B/s"
    }
}

/** One decimal place, without platform locale surprises ("1.5", never "1,5"). */
private fun fmt1(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}"
}

/**
 * The diagnostics overlay over a live session's picture: which graphics path and codec the server
 * is on, how fast frames arrive and get drawn, and where the time goes. Every rate is a delta over
 * the last poll interval, so the counters themselves never reset.
 */
@Composable
fun RemoteStatsOverlay(screen: RemoteDesktopScreenState, modifier: Modifier = Modifier) {
    var values by remember { mutableStateOf<RemoteStatsValues?>(null) }
    LaunchedEffect(screen) {
        var previous = sampleOf(screen)
        var mark = TimeSource.Monotonic.markNow()
        while (true) {
            delay(POLL_MS)
            val now = sampleOf(screen)
            values = remoteStatsValues(previous, now, mark.elapsedNow().inWholeMilliseconds)
            previous = now
            mark = TimeSource.Monotonic.markNow()
        }
    }
    val v = values ?: return
    Column(
        modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.surfaceDeep.copy(alpha = 0.88f))
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatRow(stringResource(Res.string.rd_stats_path), v.path)
        StatRow(stringResource(Res.string.rd_stats_codec), v.codec)
        v.negotiated?.let { StatRow(stringResource(Res.string.rd_stats_negotiated), it) }
        StatRow(stringResource(Res.string.rd_stats_server_fps), v.serverFps)
        StatRow(stringResource(Res.string.rd_stats_redraw_fps), v.redrawFps)
        StatRow(stringResource(Res.string.rd_stats_decode), v.decodeMs)
        StatRow(stringResource(Res.string.rd_stats_bridge), v.bridgeMs)
        StatRow(stringResource(Res.string.rd_stats_draw), v.drawMs)
        StatRow(stringResource(Res.string.rd_stats_dropped), v.dropped)
        StatRow(stringResource(Res.string.rd_stats_repaints), v.repaints)
        StatRow(stringResource(Res.string.rd_stats_in), v.rateIn)
        StatRow(stringResource(Res.string.rd_stats_out), v.rateOut)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(label, color = Skerry.colors.faint, size = 10.5.sp, modifier = Modifier.width(STAT_LABEL_WIDTH))
        Txt(value, color = Skerry.colors.text, size = 10.5.sp)
    }
}

private fun sampleOf(screen: RemoteDesktopScreenState) = RemoteStatsSample(
    diagnostics = screen.diagnostics.snapshot(),
    redraws = screen.frame,
    drawNanos = screen.renderStats.drawNanos,
    drawCount = screen.renderStats.drawCount,
    bridgeNanos = screen.renderStats.bridgeNanos,
    bridgeCount = screen.renderStats.bridgeCount,
)

private const val POLL_MS = 1000L
private val STAT_LABEL_WIDTH = 128.dp
