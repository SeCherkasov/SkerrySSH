package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-fit loop end to end (issue #180): a live [TerminalScreen] on a narrow scene, wide
 * output arrives, and the machine on [TerminalScreenState.autoFit] has to drive the real
 * metrics -> resize -> reflow cycle to a converged scale — and then hold it. The machine's rules
 * are unit-tested in TerminalAutoFitTest; what only this test can see is the loop's plumbing:
 * a settled-snapshot gate that never opens (nothing converges) or one that doesn't gate
 * (the scale runs to the floor past the fit).
 */
class TerminalAutoFitLiveTest {

    /** Replays scripted shell output on demand; input/resize are accepted and dropped. */
    private class ScriptedSession : TerminalSession {
        private val stateFlow = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = stateFlow

        /** Connection loss: what the Disconnected screen freezes over. */
        fun drop() {
            stateFlow.value = TerminalState.Closed()
        }
        // Replay covers prints landing before the collector attaches; the extra buffer absorbs
        // the streaming-pressure phase, whose prints outpace the emulator's drain.
        private val chunks = MutableSharedFlow<ByteArray>(replay = 8, extraBufferCapacity = 64)
        override val output: Flow<ByteArray> = chunks

        fun print(text: String) {
            check(chunks.tryEmit(text.encodeToByteArray()))
        }

        override suspend fun send(data: ByteArray) = Unit
        override suspend fun resize(size: PtySize) = Unit
        override suspend fun close() = Unit
    }

    /** Virtual frame clock, shared across wait phases so scene time never runs backwards. */
    private var frame = 0L

    @OptIn(ExperimentalComposeUiApi::class)
    private fun autoFitScene(terminal: TerminalScreenState): ImageComposeScene =
        ImageComposeScene(width = 300, height = 400, density = Density(1f)) {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(rememberUiFont(), rememberMono(), rememberMaterialSymbols()),
                ) {
                    Box(Modifier.fillMaxSize().background(Skerry.colors.terminalBg)) {
                        TerminalScreen(terminal, Modifier.fillMaxSize(), autoFitEnabled = true)
                    }
                }
            }
        }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `wide output converges the font once and a later wider line does not move it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ScriptedSession()
        val terminal = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        var scene = autoFitScene(terminal)
        try {
            // Let the first layout resize land before printing, as on a live connect: output fed
            // into the default 80x24 grid would get pushed into scrollback by the initial reflow,
            // and scrollback is deliberately outside the fit.
            waitForFrames(scene) { terminal.cols in 20..50 }
            // A 50-char line on a ~34-col grid soft-wraps; the fit has to land well above the 6px
            // floor, so a floor-runaway is distinguishable from an honest convergence. The cursor
            // ends on its own short prompt line — the line being typed is excluded on purpose.
            // More wide lines keep streaming in WHILE the fit converges — the step-per-settled
            // discipline must hold under exactly this pressure (snapshots published between a
            // step and its reflow still carry the old grid and must not drive extra steps).
            // Best-effort race amplifier, not a deterministic reproduction: the prints ride the
            // test's frame cadence, so whether one lands inside the step-to-restart window is
            // scheduling luck — a run that hits it fails loudly, a run that misses proves less.
            session.print("ok\r\n" + "x".repeat(50) + "\r\n$ ")
            var pressure = 0
            waitForFrames(scene) {
                if (pressure < 12 && !terminal.autoFit.converged) {
                    session.print("x".repeat(50) + "\r\n$ ")
                    pressure++
                }
                terminal.autoFit.converged
            }
            assertTrue(terminal.autoFit.converged, "auto-fit did not converge; scale=${terminal.autoFit.scale}")
            val converged = terminal.autoFit.scale
            assertTrue(converged < 1f, "converged without shrinking")
            val floor = autoFitFloor(13)
            assertTrue(converged > floor, "ran to the floor past the fit: $converged")

            // Once per session: an even wider line afterwards must not move a converged scale.
            session.print("y".repeat(90) + "\r\n$ ")
            waitForFrames(scene, framesAfterSettled = 30) { false }
            assertEquals(converged, terminal.autoFit.scale, "a converged scale moved on later output")

            // Remount survival: the fit lives on TerminalScreenState, not in composition — a
            // remount of the screen against the same state (switching to another session's tab
            // and back, or the Disconnected screen replacing the live one) must keep the
            // converged scale instead of re-running convergence from 100%. The session is
            // dropped first for the Disconnected shape; note the converged gate returns before
            // the closed-session gate here — the frozen pre-convergence step is pinned by the
            // dedicated test below, not by this phase.
            session.drop()
            scene.close()
            scene = autoFitScene(terminal)
            waitForFrames(scene, framesAfterSettled = 30) { false }
            assertEquals(converged, terminal.autoFit.scale, "a remount reset the converged scale")
            assertTrue(terminal.autoFit.converged, "a remount re-armed convergence")
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    /**
     * A dead session must never take a step: its command queue is closed with the output, so a
     * step could only rescale the frozen glyphs without re-wrapping them — the exact effect the
     * Disconnected screen declines to offer manually. The drop lands BEFORE the wide line, so the
     * machine is still Waiting when the wrapped rows appear on the frozen screen: without the
     * closed-session gate the very next settled snapshot would take one 0.9x step.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a frozen session never takes a reflow-less step`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ScriptedSession()
        val terminal = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val scene = autoFitScene(terminal)
        try {
            waitForFrames(scene) { terminal.cols in 20..50 } // first layout resize has landed
            session.drop()
            session.print("ok\r\n" + "x".repeat(50) + "\r\n$ ")
            waitForFrames(scene, framesAfterSettled = 30) { false }
            assertEquals(1f, terminal.autoFit.scale, "a frozen session's wide output moved the scale")
            assertFalse(terminal.autoFit.converged, "a frozen session converged")
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    /**
     * Renders frames in real time until [done] (or a timeout — the resize debounce is a real
     * 150ms per convergence step, so the loop needs seconds, not frames). With [framesAfterSettled]
     * set, renders exactly that many more frames instead — "assert nothing changes" needs a fixed
     * observation window, not an exit condition.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun waitForFrames(
        scene: ImageComposeScene,
        framesAfterSettled: Int = 0,
        done: () -> Boolean,
    ) {
        if (framesAfterSettled > 0) {
            repeat(framesAfterSettled) {
                scene.render(++frame * 16_000_000L)
                Thread.sleep(32)
            }
            return
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) {
            scene.render(++frame * 16_000_000L)
            Thread.sleep(32)
        }
    }
}
