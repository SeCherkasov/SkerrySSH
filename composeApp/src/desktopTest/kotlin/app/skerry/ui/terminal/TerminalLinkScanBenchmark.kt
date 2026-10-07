package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermSnapshotRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Opt-in comparison of the existing window scan and the incremental composition path.
 * SKERRY_BENCH=1 ./gradlew :composeApp:desktopTest -Pskerry.bench \
 *     --tests '*TerminalLinkScanBenchmark*' --rerun --no-configuration-cache --info
 * Fixtures are built before timing, Kover is off, medians have no pass/fail threshold.
 * Measures URL detection, not drawing, total CPU, FPS or key-to-pixel latency.
 */
class TerminalLinkScanBenchmark {
    private class Frame(val screen: List<List<TermCell>>, val window: IntRange)
    private data class Sample(val micros: Long, val scans: Int, val spans: Int)

    @Test
    fun windowScanComparison() {
        if (System.getenv("SKERRY_BENCH") != "1") return
        val urlRows = List(24) { row("GET https://example.com/$it status=200") }
        val logRows = List(24) { row("INFO worker=$it processed 42 records successfully") }
        val history = List(1_024) { row("GET https://example.com/$it status=200") }
        val workloads = listOf(
            "status-urls" to updates(urlRows),
            "status-logs" to updates(logRows),
            "scroll" to List(1_000) { Frame(history, it..it + 23) },
            "full-redraw" to List(1_000) { frame ->
                Frame(List(24) { row("GET https://example.com/$it tick=$frame") }, 0..23)
            },
        )
        for ((name, frames) in workloads) {
            repeat(3) { scan(frames, cached = false); scan(frames, cached = true) }
            val baseline = ArrayList<Sample>()
            val cached = ArrayList<Sample>()
            // Alternate order so the candidate does not always benefit from a later hot JVM.
            repeat(5) { sample ->
                if (sample % 2 == 0) {
                    baseline += scan(frames, cached = false)
                    cached += scan(frames, cached = true)
                } else {
                    cached += scan(frames, cached = true)
                    baseline += scan(frames, cached = false)
                }
            }
            val before = baseline.sortedBy { it.micros }[2]
            val after = cached.sortedBy { it.micros }[2]
            assertEquals(before.spans, after.spans, "both paths must report the same visible spans")
            assertEquals(if (name == "full-redraw") 24_000 else 1_023, after.scans)
            assertEquals(24_000, before.scans)
            println("BENCH links $name baseline=${before.micros}us cached=${after.micros}us " +
                "scans=${before.scans}->${after.scans} spans=${after.spans}")
        }
    }

    private fun row(text: String): List<TermCell> = TermSnapshotRow(text.map { TermCell(it) }, false)

    private fun updates(rows: List<List<TermCell>>): List<Frame> = List(1_000) { frame ->
        val changed = rows.toMutableList()
        changed[23] = row("status tick=$frame")
        Frame(changed, 0..23)
    }

    private fun scan(frames: List<Frame>, cached: Boolean): Sample {
        val cache = if (cached) LinkScanCache() else null
        val before = linkChainScans
        var spans = 0
        val elapsed = measureTime {
            for (frame in frames) {
                val found = linkSpansByRow(frame.screen, frame.window, cache)
                for (row in found.values) spans += row.size
            }
        }
        return Sample(elapsed.inWholeMicroseconds, linkChainScans - before, spans)
    }
}
