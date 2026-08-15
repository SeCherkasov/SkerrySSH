package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a watched session actually looks like: the colleague's terminal rendered by the real
 * emulator (a viewer is a live [TerminalSession], not a screenshot feed), with the sharing hints
 * over it. Written to `build/reports/shared-session-live.png` for visual review.
 */
class SharedSessionLiveRenderTest {

    /** Stands in for the relay: replays what the host's shell printed, then holds the session open. */
    private class WatchedSession : TerminalSession {
        override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
        override val output: Flow<ByteArray> = flow {
            emit(
                (
                    "Last login: Sun Jul 27 22:14:03 2026 from 10.0.12.4\r\n" +
                        "\u001b[32mstan@serverauditor\u001b[0m:\u001b[34m~\u001b[0m# netstat -tulpn | tail -4\r\n" +
                        "Active Internet connections (only servers)\r\n" +
                        "tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN\r\n" +
                        "tcp6       0      0 :::80                   :::*                    LISTEN\r\n" +
                        "tcp6       0      0 :::16734                :::*                    LISTEN\r\n" +
                        "\u001b[32mstan@serverauditor\u001b[0m:\u001b[34m~\u001b[0m# systemctl restart ngin"
                    ).encodeToByteArray(),
            )
            awaitCancellation()
        }

        override suspend fun send(data: ByteArray) = Unit
        override suspend fun resize(size: PtySize) = Unit
        override suspend fun close() = Unit
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a watched session renders as a live terminal with the sharing hints over it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = TerminalScreenState(WatchedSession(), scope, nowMillis = eagerPublishClock())
        val scene = ImageComposeScene(width = 900, height = 200, density = Density(1f)) {
            SkerryTheme {
                CompositionLocalProvider(
                    // The real bundled families, as the app uses them: with the platform fallbacks
                    // every icon renders as its own name instead of a glyph.
                    LocalFonts provides DesignFonts(rememberUiFont(), rememberMono(), rememberMaterialSymbols()),
                ) {
                    Box(Modifier.fillMaxSize().background(Skerry.colors.terminalBg)) {
                        // Live mode is the terminal plus one hint under the cursor — everything the
                        // host or a viewer can act on lives in the share panel, not over the screen.
                        TerminalScreen(
                            terminal,
                            Modifier.fillMaxSize(),
                            focused = true,
                            // Pinned grid: the scene then holds exactly these rows, so where the
                            // caret lands relative to the cursor is unambiguous in the picture.
                            fixedGrid = PtySize(cols = 96, rows = 8),
                            cursorOverlay = { modifier -> CollaboratorCaret("anna@corp.io", modifier = modifier) },
                        )
                    }
                }
            }
        }
        var image = scene.render(0)
        for (frame in 1..60) {
            image = scene.render(frame * 16_000_000L)
            Thread.sleep(8)
        }
        val data = image.encodeToData() ?: error("encode failed")
        val out = File("build/reports/shared-session-live.png")
        out.parentFile.mkdirs()
        out.writeBytes(data.bytes)
        scene.close()
        scope.cancel()

        assertTrue(out.length() > 0, "nothing rendered")
    }
}
