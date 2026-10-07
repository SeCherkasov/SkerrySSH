package app.skerry.ui.terminal

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.highlight.HighlightKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Opt-in style-cache comparison against the original unbounded map. No timing thresholds:
 * SKERRY_BENCH=1 ./gradlew :composeApp:desktopTest -Pskerry.bench \
 *     --tests '*TerminalGlyphStyleCacheBenchmark*' --rerun --no-configuration-cache --info
 * Measures style resolution only, not layout, FPS, input latency or total process memory.
 */
class TerminalGlyphStyleCacheBenchmark {
    private val base = TextStyle(fontSize = 14.sp)
    private val theme = TerminalThemes.NightSea

    @Test
    fun retentionAndResolution() {
        if (System.getenv("SKERRY_BENCH") != "1") return
        val colors = List(50_000) { index ->
            TermStyle(fg = TermColor.Rgb(index shr 16, (index shr 8) and 255, index and 255))
        }
        val workloads = listOf(
            "palette" to List(colors.size) { colors[it % 32] },
            "truecolor" to colors,
            "colorful-viewport" to List(80 * 24 * 3) { colors[it % (80 * 24)] },
        )
        for ((name, styles) in workloads) {
            for (bounded in listOf(false, true)) {
                repeat(3) { resolve(styles, bounded) }
                val samples = List(5) { resolve(styles, bounded) }.sortedBy { it.micros }
                val sample = samples[samples.size / 2]
                println("BENCH $name ${if (bounded) "bounded" else "baseline"}: " +
                    "median=${sample.micros}us retained=${sample.retained} created=${sample.created}")
                val expected = styles.distinct().size
                assertEquals(if (bounded) minOf(expected, 80 * 24 * 2) else expected, sample.retained)
                assertEquals(expected, sample.created, "a viewport within the budget must not thrash")
            }
        }
    }

    private data class Sample(val micros: Long, val retained: Int, val created: Int)

    private fun resolve(styles: List<TermStyle>, bounded: Boolean): Sample {
        val cache = GlyphStyleCache(1024).apply { fitWindow(80 * 24) }
        // Frozen baseline from TerminalScreen before bounding; keep its original HashMap lookup.
        val baseline = HashMap<TermStyle, Array<TextStyle?>>()
        var created = 0
        val elapsed = measureTime {
            for (style in styles) {
                if (bounded) {
                    cache.style(style, HighlightKind.None) {
                        created++
                        style.toGlyphStyle(base, emptyMap(), theme)
                    }
                } else {
                    val byKind = baseline.getOrPut(style) { arrayOfNulls(HIGHLIGHT_KIND_COUNT) }
                    if (byKind[HighlightKind.None.ordinal] == null) {
                        created++
                        byKind[HighlightKind.None.ordinal] = style.toGlyphStyle(base, emptyMap(), theme)
                    }
                }
            }
        }
        return Sample(elapsed.inWholeMicroseconds, if (bounded) cache.size else baseline.size, created)
    }
}
