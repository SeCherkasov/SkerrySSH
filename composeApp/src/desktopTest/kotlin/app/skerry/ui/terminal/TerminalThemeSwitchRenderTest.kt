package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.render.SceneFrames
import kotlin.test.Test
import kotlin.test.assertFalse
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
 * Regression test for "terminal theme switch doesn't recolor everything until tab switch": renders
 * a live [TerminalScreen] offscreen, swaps [LocalTerminalTheme] at runtime (as Appearance does via
 * [app.skerry.ui.desktop.DesktopDesignApp]), and checks pixels on both render paths: cell background
 * fill (SGR 44 -> drawRect) and glyphs (SGR 32 + U+2588 FULL BLOCK -> drawText). The glyph path used
 * to go stale because the [androidx.compose.ui.text.TextMeasurer] cache compares styles only by
 * layout attributes (color excluded), and the `drawText(measurer, ...)` overload paints with the
 * color baked into the cached paragraph, leaving glyphs in the old palette until the cache was reset.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalThemeSwitchRenderTest {

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
    fun themeSwitchRecolorsOpenTerminalWithoutTabSwitch() {
        // Unconfined: feed/resize run synchronously at the emit point, making test frames deterministic.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val theme = mutableStateOf(TerminalThemes.NightSea)
        try {
            ImageComposeScene(width = 400, height = 260, density = Density(1f)).use { scene ->
                scene.setContent {
                    CompositionLocalProvider(LocalTerminalTheme provides theme.value) {
                        TerminalScreen(state, Modifier.fillMaxSize())
                    }
                }
                val frames = SceneFrames(scene)

                // First frames: layout -> LaunchedEffect(resize) -> sized=true. Emit output only after
                // the grid settles: shrinking from 80x24 to the actual grid pushes top rows into
                // scrollback under autoscroll, so the test rows must stay on screen.
                frames.settle(LAYOUT_FRAMES)
                // Line 1: SGR 44, background fill (drawRect). Line 2: SGR 32 + 3x U+2588 FULL BLOCK,
                // solid glyphs (drawText). Exactly three blocks: the TextMeasurer cache holds 8
                // entries, and all keys must fit in it (as with a few short user runs), or LRU eviction
                // would mask a stale-color bug.
                session.emit("\u001b[44m          \u001b[0m\r\n\u001b[32m███\u001b[0m")

                // Both old-palette render paths have to have drawn before the switch means anything.
                frames.awaitFrame("the SGR 44 fill in the Night Sea palette") { it.hasColor(NIGHT_SEA_ANSI_BLUE) }
                frames.awaitFrame("the SGR 32 glyphs in the Night Sea palette") { it.hasColor(NIGHT_SEA_ANSI_GREEN) }

                // Switch theme at runtime, as clicking the Appearance card does.
                theme.value = TerminalThemes.SolarizedLight

                frames.awaitFrame("the terminal background to recolor to Solarized Light") { it.hasColor(SOLARIZED_BG) }
                frames.awaitFrame("the SGR 44 fill to recolor to Solarized blue") { it.hasColor(SOLARIZED_ANSI_BLUE) }
                // The last of the three positives is also where both negatives are read: the repaint this
                // test is about is one frame carrying the new palette and nothing of the old, not two
                // conditions that may hold frames apart.
                val recolored = frames.awaitFrame("the SGR 32 glyphs to recolor to Solarized green") {
                    it.hasColor(SOLARIZED_ANSI_GREEN)
                }
                assertFalse(
                    recolored.hasColor(NIGHT_SEA_ANSI_BLUE),
                    "the SGR 44 fill is still Night Sea blue: the background layer did not repaint",
                )
                assertFalse(
                    recolored.hasColor(NIGHT_SEA_ANSI_GREEN),
                    "the SGR 32 glyphs are still Night Sea green: drawText painted from a stale TextMeasurer cache",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** Whether the frame contains any pixel of exact color [argb] (step-1 scan; the scene is small). */
    private fun PixelMap.hasColor(argb: Int): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (this[x, y].toArgb() == argb) return true
            }
        }
        return false
    }

    private companion object {
        /** Frames spent letting layout settle before the first emit — nothing is asserted about them. */
        const val LAYOUT_FRAMES = 3

        val NIGHT_SEA_ANSI_BLUE = 0xFF4A9EDB.toInt()
        val NIGHT_SEA_ANSI_GREEN = 0xFF5DCE9E.toInt()
        val SOLARIZED_BG = 0xFFFDF6E3.toInt()
        val SOLARIZED_ANSI_BLUE = 0xFF268BD2.toInt()
        val SOLARIZED_ANSI_GREEN = 0xFF859900.toInt()
    }
}
