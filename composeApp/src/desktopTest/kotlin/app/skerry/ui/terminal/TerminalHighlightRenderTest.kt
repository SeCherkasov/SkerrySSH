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
 * Renders a live [TerminalScreen] offscreen and checks that syntax highlighting reaches the glyphs:
 * a typed command turns the theme's green, the switch actually turns it off, and a color the server
 * chose itself is never overpainted. The categorization is covered by the tokenizer's own tests;
 * this is the draw path.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalHighlightRenderTest {

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

    private val theme = TerminalThemes.NightSea

    /** Runs [body] against a rendered terminal with the given highlight settings. */
    private fun withScreen(
        highlight: TerminalHighlight,
        body: (session: FakeSession, frame: () -> PixelMap) -> Unit,
    ) {
        // Unconfined: feed/resize run synchronously at the emit point, making frames deterministic.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides highlight,
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
                // Let layout settle (resize -> sized=true) before emitting.
                repeat(3) { frame() }
                body(session, ::frame)
            }
        } finally {
            scope.cancel()
        }
    }

    /** Renders a few frames and reports whether [color] ended up on screen. */
    private fun settle(frame: () -> PixelMap, color: Color): Boolean {
        var pixels = frame()
        repeat(4) { pixels = frame() }
        return pixels.hasColorNear(color.toArgb())
    }

    @Test
    fun typedCommandIsPaintedInTheThemeGreen() {
        withScreen(TerminalHighlight(commandLine = true, output = false)) { session, frame ->
            session.emit("user@host:~$ git status")
            assertTrue(settle(frame, theme.ansi[2]), "a known command should be drawn in the theme's green")
        }
    }

    @Test
    fun theSwitchTurnsHighlightingOff() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frame ->
            session.emit("user@host:~$ git status")
            assertFalse(settle(frame, theme.ansi[2]), "nothing may be recolored while the switch is off")
        }
    }

    @Test
    fun outputLevelsArePaintedOnlyWhenAskedFor() {
        withScreen(TerminalHighlight(commandLine = false, output = true)) { session, frame ->
            session.emit("\r\nERROR failed to bind\r\n")
            assertTrue(settle(frame, theme.ansi[1]), "a log level should be drawn in the theme's red")
        }
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frame ->
            session.emit("\r\nERROR failed to bind\r\n")
            assertFalse(settle(frame, theme.ansi[1]), "output stays plain while the switch is off")
        }
    }

    @Test
    fun theServerWinsTheArgumentAboutColor() {
        withScreen(TerminalHighlight(commandLine = true, output = true)) { session, frame ->
            // The server prints ERROR in its own green; the client's rule would make it red.
            session.emit("\r\n\u001b[32mERROR failed to bind\u001b[0m\r\n")
            var pixels = frame()
            repeat(4) { pixels = frame() }
            assertTrue(pixels.hasColorNear(theme.ansi[2].toArgb()), "the server's green must survive")
            assertFalse(pixels.hasColorNear(theme.ansi[1].toArgb()), "an already-colored cell must not be repainted")
        }
    }

    @Test
    fun anExecutedCommandKeepsItsColorAfterEnter() {
        // The regression a user hit: the command went plain the moment it ran. The state is driven
        // through typeInput so the executed-command set is populated the way it is in a session.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = true, output = false),
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
                repeat(3) { frame() }

                session.emit("user@host:~$ ")
                state.typeInput("git status")
                session.emit("git status")
                state.typeInput("\r")
                // The shell echoes the newline and draws the next prompt; the command is now history.
                session.emit("\r\nuser@host:~$ ")

                var pixels = frame()
                repeat(5) { pixels = frame() }
                assertTrue(
                    pixels.hasColorNear(theme.ansi[2].toArgb()),
                    "the executed command must stay green once the cursor moves to the next prompt",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** Whether opaque pixel [argb] matches [target] within [tolerance] on every channel. */
    private fun matches(argb: Int, target: Int, tolerance: Int): Boolean {
        if ((argb ushr 24) != 0xFF) return false
        for (shift in intArrayOf(16, 8, 0)) {
            if (abs((argb shr shift and 0xFF) - (target shr shift and 0xFF)) > tolerance) return false
        }
        return true
    }

    /** Whether any pixel is within [tolerance] per channel of [argb] (antialiasing shifts edges). */
    private fun PixelMap.hasColorNear(argb: Int, tolerance: Int = 2): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matches(this[x, y].toArgb(), argb, tolerance)) return true
            }
        }
        return false
    }
}
