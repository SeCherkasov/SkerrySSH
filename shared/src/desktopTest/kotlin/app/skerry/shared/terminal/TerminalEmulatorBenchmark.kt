package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Parser throughput on representative streams, printed rather than asserted: a timing floor would
 * fail on a loaded CI runner and say nothing about the code. Opt-in with `SKERRY_BENCH=1`:
 *
 * ```
 * SKERRY_BENCH=1 ./gradlew :shared:desktopTest -Pskerry.bench --tests '*TerminalEmulatorBenchmark*' --rerun -i | grep BENCH
 * ```
 *
 * Without `-Pskerry.bench` the coverage agent runs inside the test JVM and the numbers come out
 * about half of what the code does.
 */
class TerminalEmulatorBenchmark {

    @Test
    fun throughput() {
        if (System.getenv("SKERRY_BENCH") != "1") return
        for ((name, data) in streams()) {
            val chunks = data.asList().chunked(CHUNK).map { it.toByteArray() }
            repeat(WARMUP_RUNS) { run(chunks) }
            val best = (1..MEASURED_RUNS).minOf { measureTime { run(chunks) } }
            val mbPerSecond = data.size / 1e6 / (best.inWholeMicroseconds / 1e6)
            println("BENCH $name: ${data.size / 1_000_000} MB, best $best, ${"%.1f".format(mbPerSecond)} MB/s")
        }
    }

    // A publish every few chunks, as the UI does: the snapshot is part of what output costs.
    private fun run(chunks: List<ByteArray>) {
        val emulator = TerminalEmulator(cols = 200, rows = 50)
        chunks.forEachIndexed { i, chunk ->
            emulator.feed(chunk)
            if (i % PUBLISH_EVERY == 0) emulator.lines
        }
    }

    private fun streams(): List<Pair<String, ByteArray>> {
        val esc = "\u001b"
        val random = java.util.Random(1)
        fun build(block: StringBuilder.() -> Unit) = StringBuilder().apply(block).toString().encodeToByteArray()
        return listOf(
            "ascii" to build {
                repeat(LINES) { append("drwxr-xr-x  2 user user  4096 Sep 26 12:00 some-file-name-$it.txt\r\n") }
            },
            "sgr" to build {
                repeat(LINES) {
                    append("$esc[01;34mdir$it$esc[0m  $esc[38;5;${it % 256}mfile$esc[0m ")
                    append("$esc[38;2;10;20;${it % 256}mrgb$esc[0m\r\n")
                }
            },
            "cup" to build {
                repeat(LINES / 5) { frame ->
                    for (row in 1..24) {
                        append("$esc[$row;1H$esc[7m${"%5d".format(frame)}$esc[0m cpu ${random.nextInt(100)}% mem$esc[K")
                    }
                }
            },
            "box" to build {
                repeat(LINES / 2) { append("│ ${"─".repeat(60)} ┼ ${"━".repeat(20)} │\r\n") }
            },
            "cjk" to build {
                repeat(LINES / 2) { append("日本語のテキスト 한국어 текст на русском ✓ 🚀\r\n") }
            },
        )
    }

    private companion object {
        const val LINES = 200_000
        const val CHUNK = 4096
        const val PUBLISH_EVERY = 4
        const val WARMUP_RUNS = 2
        const val MEASURED_RUNS = 3
    }
}
