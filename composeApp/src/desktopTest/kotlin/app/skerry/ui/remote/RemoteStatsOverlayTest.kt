package app.skerry.ui.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_stats_codec
import app.skerry.ui.generated.resources.rd_stats_path
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** The overlay composes, polls, and shows its rows — the smoke the pure value tests cannot give. */
@OptIn(ExperimentalTestApi::class)
class RemoteStatsOverlayTest {

    @Test
    fun `after the first poll the overlay shows its rows`() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val session = FakeRemoteDesktop()
        session.diagnostics.notePath("EGFX")
        session.diagnostics.noteCodec("Progressive")
        val screen = RemoteDesktopScreenState(session, scope)
        try {
            runComposeUiTest {
                setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalFonts provides
                                DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            RemoteStatsOverlay(screen)
                        }
                    }
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                onNodeWithText(string(Res.string.rd_stats_path)).assertExists()
                onNodeWithText(string(Res.string.rd_stats_codec)).assertExists()
                onNodeWithText("EGFX").assertExists()
                onNodeWithText("Progressive").assertExists()
            }
        } finally {
            scope.cancel()
        }
    }
}
