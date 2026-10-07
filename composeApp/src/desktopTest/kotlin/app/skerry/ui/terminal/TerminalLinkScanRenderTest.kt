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

/** A live status-line update must not repeat URL detection over stable rows. */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalLinkScanRenderTest {
    @Test
    fun oneRowUpdateOnlyScansThatRow() {
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
                            TerminalScreen(state, Modifier.fillMaxSize(), fixedGrid = PtySize(80, 12))
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(3)
                session.print(buildString {
                    append("\u001b[?25l")
                    repeat(12) { row -> append("\u001b[${row + 1};1Hsee https://example.com/$row") }
                    append("\u001b[1;1H")
                })
                frames.settle(6)
                val stableRow = state.screen[1]
                val passes = linkScanPasses
                val scans = linkChainScans
                session.print("\u001b[1;1Hsee https://changed.test/0\u001b[K")
                frames.awaitFrame("the updated row's link scan") { linkScanPasses > passes }
                frames.settle(6)
                assertSame(stableRow, state.screen[1], "the emulator must reuse unchanged rows")
                assertEquals(1, linkChainScans - scans, "only the changed logical line needs detection")
            }
        } finally {
            scope.cancel()
        }
    }
}
