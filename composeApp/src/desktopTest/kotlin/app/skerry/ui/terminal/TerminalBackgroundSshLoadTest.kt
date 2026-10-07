package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.HostKeyRefusal
import app.skerry.shared.ssh.HostKeyVerifier
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.terminal.ShellTerminalSession
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.render.SceneFrames
import app.skerry.ui.theme.SkerryTheme
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Opt-in Linux load test through SSHJ, five real loopback PTYs and the real TerminalScreen.
 * See docs/benchmarks/background-ssh-tabs.md for the isolated sshd fixture and environment.
 * Reports client-JVM CPU, render call durations and key-to-published-output latency; the latter
 * includes SSH round trip and does not attest display scanout or physical input latency.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TerminalBackgroundSshLoadTest {
    private data class Sample(val cpuNs: Long, val frames: Int, val p95FrameUs: Long,
        val p95InputUs: Long, val publishes: Int, val outputs: List<Int>, val scrolls: Int = 0)

    @Test
    fun fiveSshTabsUnderLoad() {
        if (System.getenv("SKERRY_SSH_LOAD") != "1") return
        check(System.getProperty("kotlinx.coroutines.debug") == "off") { "Disable coroutine debug for production CPU measurements" }
        val port = checkNotNull(System.getenv("SKERRY_SSH_PORT")).toInt()
        val fingerprint = checkNotNull(System.getenv("SKERRY_SSH_FINGERPRINT"))
        val auth = SshAuth.PublicKey(Files.readString(Path.of(checkNotNull(System.getenv("SKERRY_SSH_KEY")))))
        val verifier = HostKeyVerifier { offer ->
            if (offer.host == "127.0.0.1" && offer.port == port && offer.fingerprint == fingerprint) null
            else HostKeyRefusal.KeyChanged
        }
        val target = SshTarget("127.0.0.1", port, System.getProperty("user.name"),
            shellCommand = listOf("python3", "-u", "-c", WORKLOAD))
        val before = ArrayList<Sample>()
        val after = ArrayList<Sample>()
        // Warm both policies, then alternate order to reduce JVM warmup and host-load bias.
        measure(target, auth, verifier, false, null)
        measure(target, auth, verifier, true, null)
        repeat(3) { round ->
            for (optimized in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                val sample = measure(target, auth, verifier, optimized, null)
                if (optimized) after += sample else before += sample
                println("LOAD SSH optimized=$optimized $sample")
            }
        }
        (before + after).forEach { assertEquals(before.first().outputs, it.outputs, "same generated log output") }
        fun median(samples: List<Sample>, value: (Sample) -> Long) = samples.map(value).sorted()[1]
        println("BENCH SSH cpuNs=${median(before) { it.cpuNs }}->${median(after) { it.cpuNs }} " +
            "p95FrameUs=${median(before) { it.p95FrameUs }}->${median(after) { it.p95FrameUs }} " +
            "p95InputUs=${median(before) { it.p95InputUs }}->${median(after) { it.p95InputUs }}")
        // Sampling/JFR instrumentation has its own warmup and allocation costs. Keep profiles
        // outside the uninstrumented samples, as the pipeline comparison does above.
        System.getenv("SKERRY_BACKGROUND_JFR")?.let { directory ->
            for (optimized in listOf(false, true)) {
                val profile = Path.of(directory, "ssh-${if (optimized) "after" else "before"}.jfr")
                println("PROFILE SSH optimized=$optimized ${measure(target, auth, verifier, optimized, profile)}")
            }
        }
    }

    private fun measure(target: SshTarget, auth: SshAuth, verifier: HostKeyVerifier,
        optimized: Boolean, profile: Path?): Sample = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connections = ArrayList<SshConnection>()
        val recording = profile?.let { Recording(Configuration.getConfiguration("profile")) }
        try {
            val terminals = List(5) {
                val connection = SshjTransport(verifier).connect(target, auth)
                connections += connection
                TerminalScreenState(ShellTerminalSession(connection.openShell(PtySize(120, 32)), scope),
                    scope, scrollback = 10_000, backgroundWhenUnobserved = optimized)
            }
            withTimeout(15_000) {
                while (terminals.any { !it.output.endsWith("READY") }) delay(20)
            }
            val shown = mutableStateOf(0)
            ImageComposeScene(width = 1_000, height = 600, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) { TerminalScreen(terminals[shown.value], Modifier.fillMaxSize(), fixedGrid = PtySize(120, 32)) }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(6)
                val publishes = terminals.sumOf { it.snapshotVersion }
                recording?.start()
                val timed = exercise(scene, frames, terminals)
                recording?.stop()
                profile?.let { recording?.dump(it) }
                // Switching to each quiet hidden tab must render its final output without waiting
                // for more PTY data. All five quiet shells are still owned until scope teardown.
                for (index in terminals.indices.drop(1) + 0) {
                    val beforeSwitch = terminals[index].snapshotVersion
                    shown.value = index
                    frames.awaitFrame("terminal $index after switch") {
                        terminals[index].snapshotVersion > beforeSwitch && finished(terminals[index])
                    }
                }
                profile?.let {
                    scene.render(System.nanoTime()).use { image ->
                        Files.write(it.resolveSibling("ssh-${if (optimized) "after" else "before"}.png"),
                            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
                    }
                }
                Sample(timed.cpuNs, timed.frames, timed.p95FrameUs, timed.p95InputUs,
                    terminals.sumOf { it.snapshotVersion } - publishes,
                    terminals.map { terminal ->
                        val logs = terminal.output.lineSequence().filter { it.startsWith("INFO worker=") }.toList()
                        assertEquals(5_000, logs.size, "no generated log line may be lost")
                        logs.joinToString("\n").hashCode()
                    }, scrolls = timed.scrolls)
            }
        } finally {
            recording?.close()
            scope.coroutineContext[Job]?.cancelAndJoin()
            connections.forEach { it.disconnect() }
        }
    }

    private data class TimedWorkload(val cpuNs: Long, val frames: Int, val p95FrameUs: Long,
        val p95InputUs: Long, val scrolls: Int)

    private suspend fun exercise(scene: ImageComposeScene, frames: SceneFrames,
        terminals: List<TerminalScreenState>): TimedWorkload {
        val bean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val frameTimes = ArrayList<Long>()
        val inputTimes = ArrayList<Long>()
        val cpu = bean.processCpuTime
        terminals.forEach { it.send("go\r") }
        var frame = 0
        var scrolls = 0
        withTimeout(15_000) {
            while (terminals.any { !finished(it) }) {
                val started = System.nanoTime()
                frames.advance()
                frameTimes += (System.nanoTime() - started) / 1_000
                if (frame % 10 == 5 && inputTimes.size < 20) {
                    val key = ('a'.code + inputTimes.size).toChar()
                    val inputAt = System.nanoTime()
                    assertTrue(scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, codePoint = key.code)))
                    withTimeout(2_000) {
                        while (!hasEcho(terminals.first(), key)) {
                            frames.advance()
                            delay(2)
                        }
                    }
                    inputTimes += (System.nanoTime() - inputAt) / 1_000
                }
                if (frame % 60 == 45) {
                    scrollUp(scene, frames)
                    scrolls++
                }
                frame++
                delay(16)
            }
        }
        val elapsed = bean.processCpuTime - cpu
        assertTrue(inputTimes.size >= 10, "keyboard must be exercised under load")
        assertTrue(scrolls > 0, "scrollback must actually move under load")
        fun p95(values: List<Long>) = values.sorted()[(values.size * 95 + 99) / 100 - 1]
        return TimedWorkload(elapsed, frame, p95(frameTimes), p95(inputTimes), scrolls)
    }

    private suspend fun scrollUp(scene: ImageComposeScene, frames: SceneFrames) {
        val range = checkNotNull(scrollRange(semanticsRoot(scene))) { "terminal must expose its scroll viewport" }
        assertTrue(range.maxValue() > 0f, "history must be scrollable")
        val before = range.value()
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(500f, 300f), scrollDelta = Offset(0f, -12f))
        withTimeout(2_000) {
            while (range.value() >= before - 1f) {
                frames.advance()
                delay(2)
            }
        }
        assertTrue(range.value() < before - 1f, "wheel input must move the viewport into history")
    }

    private fun semanticsRoot(scene: ImageComposeScene): SemanticsNode {
        // Compose 1.9.3's ImageComposeScene has no public semanticsOwners accessor. This read-only,
        // test-only probe uses its pinned implementation; an API change must fail the load test,
        // rather than silently skipping the viewport assertion. No shipping code uses reflection.
        val sceneField = ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }
        val inner = sceneField.get(scene)
        val ownerField = inner.javaClass.getDeclaredField("mainOwner").apply { isAccessible = true }
        val owner = ownerField.get(inner)
        val semantics = owner.javaClass.getMethod("getSemanticsOwner").invoke(owner) as SemanticsOwner
        return semantics.unmergedRootSemanticsNode
    }

    private fun scrollRange(node: SemanticsNode): ScrollAxisRange? {
        node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { return it }
        return node.children.firstNotNullOfOrNull { scrollRange(it) }
    }

    private fun finished(terminal: TerminalScreenState): Boolean = terminal.screen.takeLast(3).any { row ->
        row.joinToString("") { it.text }.trimEnd() == "DONE"
    }

    private fun hasEcho(terminal: TerminalScreenState, key: Char): Boolean {
        val screen = terminal.screen
        for (index in (screen.size - 256).coerceAtLeast(0)..screen.lastIndex) {
            val row = screen[index]
            if (row.size < 5) continue
            if (row[4].text != key.toString()) continue
            if (row[0].text != "K" || row[1].text != "E") continue
            if (row[2].text == "Y" && row[3].text == ":") return true
        }
        return false
    }
}

private val WORKLOAD = """
import os, sys, time, tty, threading
for i in range(10100): print('history-%05d: ready' % i, end='\r\n')
print('READY', flush=True)
sys.stdin.readline()
tty.setraw(0)
def echo():
    while True:
        b = os.read(0, 1)
        if not b: return
        if b'a' <= b <= b't': os.write(1, b'KEY:' + b + b'\r\n')
threading.Thread(target=echo, daemon=True).start()
for tick in range(500):
    os.write(1, ''.join('INFO worker=%02d tick=%04d status=200\r\n' % (row, tick) for row in range(10)).encode())
    time.sleep(.01)
os.write(1, b'DONE\r\n')
while True: time.sleep(1)
""".trimIndent()
