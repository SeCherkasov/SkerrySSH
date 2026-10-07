package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Path
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in, paired CPU/allocation comparison of five terminal pipelines: one visible, four hidden.
 * JAVA_TOOL_OPTIONS=-Dkotlinx.coroutines.debug=off SKERRY_BENCH=1 ./gradlew :composeApp:desktopTest -Pskerry.bench \
 *   --tests '*TerminalBackgroundBenchmark*' --rerun --no-configuration-cache --info
 * Uses identical PTY bytes, a controlled clock, real parser/owner/Compose-state publication and
 * saturated scrollback. Measures pipeline CPU, not SSH encryption, drawing or key-to-pixel latency.
 * SKERRY_BACKGROUND_JFR=/absolute/directory optionally saves profiles for both paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalBackgroundBenchmark {
    private data class Sample(val cpuNs: Long, val allocated: Long, val publishes: Int, val digest: Int)

    @Test
    fun fiveTerminalComparison() {
        if (System.getenv("SKERRY_BENCH") != "1") return
        check(System.getProperty("kotlinx.coroutines.debug") == "off") { "Disable coroutine debug for production CPU measurements" }
        for (workload in listOf("logs", "redraw")) {
            repeat(3) { measure(workload, false); measure(workload, true) }
            val before = ArrayList<Sample>()
            val after = ArrayList<Sample>()
            repeat(7) { round ->
                if (round % 2 == 0) {
                    before += measure(workload, false)
                    after += measure(workload, true)
                } else {
                    after += measure(workload, true)
                    before += measure(workload, false)
                }
            }
            for (sample in after) assertEquals(before.first().digest, sample.digest)
            println("SAMPLES background $workload beforeCpuNs=${before.map { it.cpuNs }} afterCpuNs=${after.map { it.cpuNs }}")
            fun median(samples: List<Sample>, value: (Sample) -> Long) = samples.map(value).sorted()[3]
            println("BENCH background $workload cpuNs=${median(before) { it.cpuNs }}->${median(after) { it.cpuNs }} " +
                "allocated=${median(before) { it.allocated }}->${median(after) { it.allocated }} " +
                "publishes=${before.first().publishes}->${after.first().publishes} digest=${after.first().digest}")
            System.getenv("SKERRY_BACKGROUND_JFR")?.let { profile(workload, it) }
        }
    }

    private fun profile(workload: String, directory: String) {
        for (optimized in listOf(false, true)) Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.start()
            repeat(5) { measure(workload, optimized) }
            recording.stop()
            recording.dump(Path.of(directory, "$workload-${if (optimized) "after" else "before"}.jfr"))
        }
    }

    private fun measure(workload: String, optimized: Boolean): Sample {
        var sample: Sample? = null
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            try {
                val sessions = List(5) { BackgroundTestSession() }
                val terminals = sessions.map { TerminalScreenState(it, scope,
                    backgroundWhenUnobserved = optimized, scrollback = 10_000,
                    nowMillis = { testScheduler.currentTime }) }
                terminals.first().attachRenderer()
                terminals.forEach { it.resize(PtySize(160, 48)) }
                val history = buildString { repeat(10_100) { append("history-$it: ready\r\n") } }.encodeToByteArray()
                sessions.forEach { it.chunks.send(history) }
                testScheduler.advanceUntilIdle()
                val log = "\u001b[32mINFO\u001b[0m worker=42 completed request with status=200\r\n".encodeToByteArray()
                val redraw = buildString {
                    append("\u001b[H")
                    repeat(48) { append("\u001b[2Kworker=$it cpu=42% status=running\r\n") }
                }.encodeToByteArray()
                val chunk = if (workload == "logs") log else redraw
                val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
                val thread = Thread.currentThread().threadId()
                val cpu = bean.getThreadCpuTime(thread)
                val allocated = bean.getThreadAllocatedBytes(thread)
                val publishedBefore = terminals.sumOf { it.snapshotVersion }
                repeat(2_000) { tick ->
                    sessions.forEach { it.chunks.send(chunk) }
                    if (tick % 50 == 0) terminals.first().typeInput("x", mirror = false)
                    testScheduler.advanceTimeBy(10)
                    testScheduler.runCurrent()
                }
                sessions.forEach { it.chunks.close() }
                testScheduler.advanceUntilIdle()
                val elapsed = bean.getThreadCpuTime(thread) - cpu
                val bytes = bean.getThreadAllocatedBytes(thread) - allocated
                assertTrue(elapsed > 0 && bytes > 0)
                terminals.forEach { assertEquals(2_001L, it.outputVersion) }
                sample = Sample(elapsed, bytes,
                    terminals.sumOf { it.snapshotVersion } - publishedBefore,
                    terminals.fold(1) { digest, terminal -> 31 * digest + terminal.output.hashCode() })
            } finally {
                scope.cancel()
            }
        }
        return checkNotNull(sample)
    }
}
