package app.skerry.ui.vnc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.desktop.WithWindowInfo
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/**
 * One [VncSurface] on screen over a fake session, for the tests that drive it directly rather than
 * through the shell ([app.skerry.ui.desktop.runDesktopShell]).
 *
 * Geometry: a 100×100 desktop in a 300×200 surface at density 1 → scale 2, the image spanning
 * x = 50..250 with letterbox bars either side. The pointer tests are written against those numbers.
 *
 * A real dispatcher, not a test one: the input actor's pacing delay has to actually elapse while the
 * test polls for the writes it produced.
 *
 * [beside] is composed after the surface, in the same box — chrome for the tests about who owns the
 * keyboard. [windowInfo] drives the window's own focus, which the test scene otherwise pins to true.
 */
@OptIn(ExperimentalTestApi::class)
internal fun withVncSurface(
    windowInfo: WindowInfo? = null,
    beside: @Composable () -> Unit = {},
    body: ComposeUiTest.(FakeRemoteDesktop, RemoteDesktopScreenState) -> Unit,
) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(100, 100))
    val screen = RemoteDesktopScreenState(session, scope)
    try {
        runComposeUiTest {
            setContent {
                WithWindowInfo(windowInfo) {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalFonts provides DesignFonts(
                                FontFamily.Default,
                                FontFamily.Monospace,
                                FontFamily.Default,
                            ),
                        ) {
                            Box(Modifier.size(DpSize(300.dp, 200.dp))) {
                                VncSurface(screen)
                                beside()
                            }
                        }
                    }
                }
            }
            waitForIdle()
            body(session, screen)
        }
    } finally {
        scope.cancel()
    }
}
