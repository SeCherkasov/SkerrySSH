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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Style eviction must leave the cached row layouts usable on the next repaint. */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalGlyphStyleCacheRenderTest {
    @Test
    fun truecolorStreamThenSelectionKeepsMeasuredRows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ScriptedSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 800, height = 500, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides TerminalThemes.NightSea,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize(), fixedGrid = PtySize(80, 24))
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(3)
                session.print("\u001b[?25lstable\r\nunchanged")
                frames.settle(6)
                val stableRow = state.screen[0]
                // 5,280 painted styles exceed the two-window budget (3,840) without scrolling
                // away the stable rows. Each cell has its own run, so every style reaches drawing.
                repeat(3) { frame ->
                    val measured = glyphLayoutMeasures
                    session.print(colorFrame(frame))
                    frames.awaitFrame("the colorful window to be laid out") { glyphLayoutMeasures - measured >= 80 * 22 }
                    frames.settle(6)
                    assertSame(stableRow, state.screen[0])
                }
                val measured = glyphLayoutMeasures
                val segmentations = glyphRunSegmentations
                state.beginSelection(TerminalPos(0, 0))
                state.extendSelection(TerminalPos(0, 4))
                frames.settle(6)
                assertEquals(measured, glyphLayoutMeasures, "eviction must not change the layout generation")
                assertEquals(segmentations, glyphRunSegmentations, "selection must reuse the row runs")
            }
        } finally {
            scope.cancel()
        }
    }

    private fun colorFrame(frame: Int): String = buildString {
        for (row in 2 until 24) {
            append("\u001b[${row + 1};1H")
            repeat(80) { col ->
                val rgb = frame * 80 * 22 + (row - 2) * 80 + col
                append("\u001b[38;2;128;${rgb shr 8};${rgb and 255}mx")
            }
        }
        append("\u001b[0m\u001b[1;1H")
    }
}
