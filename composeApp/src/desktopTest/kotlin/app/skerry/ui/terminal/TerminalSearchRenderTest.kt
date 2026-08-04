package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Renders a live [TerminalScreen] offscreen and checks that a scrollback search actually paints:
 * every hit gets the theme's match wash and the selected one a stronger wash, so "3 of 17" is
 * visible on the grid rather than only in the panel's counter. The state-level behavior is covered
 * by TerminalScreenStateTest; this is the draw path (the highlight layer between cell backgrounds
 * and glyphs).
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalSearchRenderTest {

    /** Fake PTY session: output only, input/resize are no-ops. */
    private class FakeSession : TerminalSession {
        private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = _state.asStateFlow()
        private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val output: Flow<ByteArray> = _output.asSharedFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(size: PtySize) {}
        override suspend fun close() {}
        fun emit(text: String) {
            check(_output.tryEmit(text.encodeToByteArray())) { "output buffer overflow" }
        }
    }

    @Test
    fun searchPaintsEveryHitAndMarksTheSelectedOne() {
        // Unconfined: feed/resize run synchronously at the emit point, making frames deterministic.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope)
        val theme = TerminalThemes.NightSea
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    // The search panel draws design primitives (icons/labels), so the scene needs
                    // the app's theme and font set; system families stand in for the bundled fonts.
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                var timeNanos = 0L
                fun frame(): PixelMap {
                    Snapshot.sendApplyNotifications()
                    timeNanos += 16_666_667L
                    return scene.render(timeNanos).toComposeImageBitmap().toPixelMap()
                }

                // Let layout settle (resize -> sized=true) before emitting, so the rows stay on screen.
                repeat(3) { frame() }
                // Blank lines first: the panel is pinned to the pane's top-right corner and would
                // otherwise cover the very rows whose highlight this test looks for.
                session.emit("\r\n\r\n\r\n\r\nalpha one\r\nalpha two")

                val match = theme.searchMatch.over(theme.background)
                val current = theme.searchCurrentMatch.over(theme.background)
                var pixels = frame()
                repeat(3) { pixels = frame() }
                assertFalse(pixels.hasColorNear(match), "nothing is highlighted before a search runs")

                state.search.open()
                state.search.updateQuery("alpha")
                assertTrue(state.search.matches.size == 2, "expected both rows to match, got ${state.search.matches}")

                pixels = frame()
                var attempts = 0
                while (!(pixels.hasColorNear(match) && pixels.hasColorNear(current)) && attempts < 30) {
                    pixels = frame()
                    attempts++
                }
                assertTrue(pixels.hasColorNear(match), "an unselected hit should carry the match wash")
                assertTrue(pixels.hasColorNear(current), "the selected hit should carry the stronger wash")

                // Closing the panel drops the highlight with it.
                state.search.close()
                pixels = frame()
                repeat(2) { pixels = frame() }
                assertFalse(pixels.hasColorNear(match), "highlight should disappear once search is closed")
                assertFalse(pixels.hasColorNear(current), "selected-hit highlight should disappear too")
            }
        } finally {
            scope.cancel()
        }
    }

    /** This translucent color composited over opaque [background] (source-over), as Skia draws it. */
    private fun Color.over(background: Color): Int {
        fun mix(src: Float, dst: Float) = ((src * alpha + dst * (1 - alpha)) * 255f).roundToInt()
        return Color(mix(red, background.red), mix(green, background.green), mix(blue, background.blue)).toArgb()
    }

    /** Whether any pixel is within [tolerance] per channel of [argb] (rounding differs by a unit). */
    private fun PixelMap.hasColorNear(argb: Int, tolerance: Int = 2): Boolean {
        fun close(a: Int, b: Int) = abs(a - b) <= tolerance
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = this[x, y].toArgb()
                if (close(p shr 16 and 0xFF, argb shr 16 and 0xFF) &&
                    close(p shr 8 and 0xFF, argb shr 8 and 0xFF) &&
                    close(p and 0xFF, argb and 0xFF) &&
                    (p ushr 24) == 0xFF
                ) {
                    return true
                }
            }
        }
        return false
    }
}
