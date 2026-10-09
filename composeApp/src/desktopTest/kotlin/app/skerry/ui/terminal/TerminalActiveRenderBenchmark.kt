package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalPos
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.render.SceneFrames
import app.skerry.ui.theme.SkerryTheme
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat

/**
 * Opt-in real TerminalScreen replay: ASCII/color logs, a box-drawing TUI and mixed Unicode.
 * SKERRY_BENCH=1 JAVA_TOOL_OPTIONS=-Dkotlinx.coroutines.debug=off ./gradlew \
 *     :composeApp:desktopTest -Pskerry.bench --tests '*TerminalActiveRenderBenchmark*' --rerun
 * Use the project's bounded systemd scope and resource preflight when running locally.
 * Reports replay plus offscreen render work, not display scanout, GPU FPS or physical input latency.
 * SKERRY_ACTIVE_RENDER_ARTIFACTS optionally saves separate JFR runs and PNGs outside the worktree.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalActiveRenderBenchmark {
    private enum class Content { Ascii, Boxes, Unicode }
    private enum class Action { Status, Selection }

    private data class Sample(
        val p95Us: Long,
        val p99Us: Long,
        val over16Ms: Int,
        val cpuNs: Long,
        val allocatedBytes: Long,
        val segmentations: Int,
        val layouts: Int,
    )

    @Test
    fun activeTerminalRepaints() {
        if (System.getenv("SKERRY_BENCH") != "1") return
        check(System.getProperty("kotlinx.coroutines.debug") == "off")
        val artifacts = System.getenv("SKERRY_ACTIVE_RENDER_ARTIFACTS")?.let { Path.of(it) }
        artifacts?.let { Files.createDirectories(it) }
        // Alternate content order across fresh scenes; keep profile instrumentation out of samples.
        repeat(3) { round ->
            val order = Content.entries.let { if (round % 2 == 0) it else it.reversed() }
            for (content in order) for (action in Action.entries) {
                println("BENCH active content=$content action=$action round=$round ${measure(content, action)}")
            }
        }
        artifacts?.let { directory ->
            for (content in Content.entries) for (action in Action.entries) {
                val path = directory.resolve("${content.name}-${action.name}")
                println("PROFILE active content=$content action=$action ${measure(content, action, path)}")
            }
        }
    }

    private fun measure(content: Content, action: Action, artifact: Path? = null): Sample {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ScriptedSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 1_600, height = 1_000, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides TerminalThemes.NightSea,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) { TerminalScreen(state, Modifier.fillMaxSize(), fixedGrid = PtySize(COLS, ROWS)) }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(3)
                session.print(screen(content))
                frames.awaitFrame("the replay to reach the terminal grid") {
                    state.screen.size == ROWS && glyphLayoutMeasures > 0 && state.screen[1][0].text == "R"
                }
                repeat(WARMUP) { step(it, action, state, session, frames) }
                val sample = profile(artifact) { sample(action, state, session, frames) }
                if (action == Action.Status) {
                    assertTrue(sample.segmentations > 0, "status writes must invalidate rendered content")
                    assertEquals("status ${SAMPLES - 1}", state.screen[0].joinToString("") { it.text }.trimEnd())
                } else {
                    assertEquals(0, sample.segmentations, "selection must reuse unchanged row runs")
                    assertEquals(0, sample.layouts, "selection must reuse unchanged text layouts")
                    assertTrue(!state.selectedText().isNullOrEmpty(), "selection must cover real text")
                }
                artifact?.let { saveImage(scene, it) }
                return sample
            }
        } finally {
            scope.cancel()
        }
    }

    private fun profile(artifact: Path?, body: () -> Sample): Sample {
        if (artifact == null) return body()
        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.start()
            val sample = body()
            recording.stop()
            recording.dump(Path.of("$artifact.jfr"))
            return sample
        }
    }

    private fun saveImage(scene: ImageComposeScene, artifact: Path) {
        scene.render(System.nanoTime()).use { image ->
            Files.write(Path.of("$artifact.png"), checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        }
    }

    private fun sample(action: Action, state: TerminalScreenState, session: ScriptedSession, frames: SceneFrames): Sample {
        val process = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val thread = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(thread.isThreadAllocatedMemorySupported)
        thread.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().threadId()
        val times = LongArray(SAMPLES)
        val segments = glyphRunSegmentations
        val layouts = glyphLayoutMeasures
        val cpu = process.processCpuTime
        val allocated = thread.getThreadAllocatedBytes(id)
        for (index in times.indices) {
            val started = System.nanoTime()
            step(index, action, state, session, frames)
            times[index] = System.nanoTime() - started
        }
        val bytes = thread.getThreadAllocatedBytes(id) - allocated
        val cpuNs = process.processCpuTime - cpu
        times.sort()
        fun percentile(percent: Int) = times[(times.size * percent + 99) / 100 - 1] / 1_000
        return Sample(percentile(95), percentile(99), times.count { it > 16_666_667L }, cpuNs, bytes,
            glyphRunSegmentations - segments, glyphLayoutMeasures - layouts)
    }

    private fun step(index: Int, action: Action, state: TerminalScreenState, session: ScriptedSession, frames: SceneFrames) {
        when (action) {
            Action.Status -> session.print("\u001b[1;1H\u001b[0m\u001b[2Kstatus $index")
            Action.Selection -> {
                state.beginSelection(TerminalPos(1, 0))
                state.extendSelection(TerminalPos(2 + index % (ROWS - 3), COLS - 2))
            }
        }
        frames.advance()
    }

    private fun screen(content: Content): String = buildString {
        append("\u001b[?25l\u001b[2J")
        for (row in 1 until ROWS) {
            append("\u001b[${row + 1};1H\u001b[0mR")
            val text = when (content) {
                Content.Ascii -> " INFO worker=$row build=ready cpu=12.7% /var/log/app.log "
                Content.Boxes -> "│ file-$row.txt ───────┼───────│ permissions rw-r--r-- │"
                Content.Unicode -> "│ 中文 文件 Русский e\u0301 → ✓ ───│ worker=$row │"
            }
            repeat(2) { index -> append("\u001b[${31 + index}m$text") }
            append("\u001b[0m\u001b[K")
        }
        append("\u001b[1;1H")
    }

    private companion object {
        const val COLS = 160
        const val ROWS = 48
        const val WARMUP = 60
        const val SAMPLES = 240
    }
}
