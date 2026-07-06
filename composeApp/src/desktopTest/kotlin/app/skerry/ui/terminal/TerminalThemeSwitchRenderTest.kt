package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
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
        val state = TerminalScreenState(session, scope)
        val theme = mutableStateOf(TerminalThemes.NightSea)
        try {
            ImageComposeScene(width = 400, height = 260, density = Density(1f)).use { scene ->
                scene.setContent {
                    CompositionLocalProvider(LocalTerminalTheme provides theme.value) {
                        TerminalScreen(state, Modifier.fillMaxSize())
                    }
                }
                var timeNanos = 0L
                fun frame(): PixelMap {
                    Snapshot.sendApplyNotifications()
                    timeNanos += 16_666_667L
                    return scene.render(timeNanos).toComposeImageBitmap().toPixelMap()
                }

                // First frames: layout -> LaunchedEffect(resize) -> sized=true. Emit output only after
                // the grid settles: shrinking from 80x24 to the actual grid pushes top rows into
                // scrollback under autoscroll, so the test rows must stay on screen.
                repeat(3) { frame() }
                // Line 1: SGR 44, background fill (drawRect). Line 2: SGR 32 + 3x U+2588 FULL BLOCK,
                // solid glyphs (drawText). Exactly three blocks: the TextMeasurer cache holds 8
                // entries, and all keys must fit in it (as with a few short user runs), or LRU eviction
                // would mask a stale-color bug.
                session.emit("\u001b[44m          \u001b[0m\r\n\u001b[32m███\u001b[0m")

                // Wait for both old-palette render paths to draw (frames for effects/snapshot publish).
                var pixels = frame()
                var attempts = 0
                while ((!pixels.hasColor(NIGHT_SEA_ANSI_BLUE) || !pixels.hasColor(NIGHT_SEA_ANSI_GREEN)) && attempts < 30) {
                    pixels = frame()
                    attempts++
                }
                assertTrue(pixels.hasColor(NIGHT_SEA_ANSI_BLUE), "did not see SGR 44 fill in the Night Sea palette")
                assertTrue(pixels.hasColor(NIGHT_SEA_ANSI_GREEN), "did not see SGR 32 glyphs in the Night Sea palette")

                // Switch theme at runtime, as clicking the Appearance card does.
                theme.value = TerminalThemes.SolarizedLight
                pixels = frame()
                pixels = frame() // second frame in case of deferred invalidation

                assertTrue(pixels.hasColor(SOLARIZED_BG), "terminal background should recolor to Solarized Light")
                assertTrue(pixels.hasColor(SOLARIZED_ANSI_BLUE), "SGR 44 fill should recolor to Solarized blue")
                if (pixels.hasColor(NIGHT_SEA_ANSI_BLUE)) {
                    fail("SGR 44 fill stayed Night Sea blue: background layer not repainted after theme switch")
                }
                if (pixels.hasColor(NIGHT_SEA_ANSI_GREEN)) {
                    fail("SGR 32 glyphs stayed Night Sea green: drawText painted with stale TextMeasurer cache color")
                }
                assertTrue(pixels.hasColor(SOLARIZED_ANSI_GREEN), "SGR 32 glyphs should recolor to Solarized green")
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
        val NIGHT_SEA_ANSI_BLUE = 0xFF4A9EDB.toInt()
        val NIGHT_SEA_ANSI_GREEN = 0xFF5DCE9E.toInt()
        val SOLARIZED_BG = 0xFFFDF6E3.toInt()
        val SOLARIZED_ANSI_BLUE = 0xFF268BD2.toInt()
        val SOLARIZED_ANSI_GREEN = 0xFF859900.toInt()
    }
}
