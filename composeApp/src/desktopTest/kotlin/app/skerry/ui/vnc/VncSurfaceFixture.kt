package app.skerry.ui.vnc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skerry.shared.graphics.RemoteDesktopUpdate
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
import kotlinx.coroutines.flow.MutableSharedFlow

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
 * [density] is the display scaling the scene is drawn at; the geometry above assumes the default 1.
 * [remoteResizeSupported] replays the server's announcement, so a test can turn resize-to-window on.
 * [surface] is the surface under test; the touch one ([VncTouchSurface]) fills the same box, so the
 * tests that only care what the surface reports about its space can be written against either.
 */
@OptIn(ExperimentalTestApi::class)
internal fun withVncSurface(
    windowInfo: WindowInfo? = null,
    density: Float = 1f,
    remoteResizeSupported: Boolean = false,
    surface: @Composable (RemoteDesktopScreenState) -> Unit = { VncSurface(it) },
    beside: @Composable () -> Unit = {},
    body: ComposeUiTest.(FakeRemoteDesktop, RemoteDesktopScreenState) -> Unit,
) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    // replay = 1: the announcement is made before the screen starts collecting it.
    val updates = MutableSharedFlow<RemoteDesktopUpdate>(replay = 1)
    if (remoteResizeSupported) updates.tryEmit(RemoteDesktopUpdate.RemoteResizeSupported)
    val session = FakeRemoteDesktop(framebuffer = RemoteFramebuffer(100, 100), updates = updates)
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
                            LocalDensity provides Density(density, LocalDensity.current.fontScale),
                        ) {
                            Box(Modifier.size(DpSize(300.dp, 200.dp))) {
                                surface(screen)
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
