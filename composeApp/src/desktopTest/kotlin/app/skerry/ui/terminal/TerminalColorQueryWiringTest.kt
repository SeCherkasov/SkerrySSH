package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.render.SceneFrames
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A mounted [TerminalScreen] hands its theme to the session, so a background query is answered
 * with the colors on screen — and with the new ones after an Appearance switch.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalColorQueryWiringTest {

    private class RecordingSession : TerminalSession {
        override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
        private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val output: Flow<ByteArray> = _output.asSharedFlow()
        val sent = CopyOnWriteArrayList<String>()
        override suspend fun send(data: ByteArray) { sent += data.decodeToString() }
        override suspend fun resize(size: PtySize) {}
        override suspend fun close() {}
        fun emit(text: String) {
            check(_output.tryEmit(text.encodeToByteArray())) { "output buffer overflow" }
        }
    }

    @Test
    fun backgroundQueryHearsTheThemeOnScreen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = RecordingSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val theme = mutableStateOf(TerminalThemes.NightSea)
        try {
            ImageComposeScene(width = 300, height = 200, density = Density(1f)).use { scene ->
                scene.setContent {
                    CompositionLocalProvider(LocalTerminalTheme provides theme.value) {
                        TerminalScreen(state, Modifier.fillMaxSize())
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(LAYOUT_FRAMES)
                session.emit(QUERY)
                assertEquals(listOf(reply(TerminalThemes.NightSea)), session.sent.filter { it.startsWith(OSC_11) })

                theme.value = TerminalThemes.SolarizedLight
                frames.settle(LAYOUT_FRAMES)
                session.emit(QUERY)
                assertEquals(
                    listOf(reply(TerminalThemes.NightSea), reply(TerminalThemes.SolarizedLight)),
                    session.sent.filter { it.startsWith(OSC_11) },
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** The expected answer, from the theme's ARGB rather than through the code under test. */
    private fun reply(theme: TerminalTheme): String {
        val argb = theme.background.toArgb()
        fun channel(shift: Int) = ((argb shr shift) and 0xff).let { "%02x%02x".format(it, it) }
        return "$OSC_11;rgb:${channel(16)}/${channel(8)}/${channel(0)}\u001b\\"
    }

    private companion object {
        const val LAYOUT_FRAMES = 3
        const val OSC_11 = "\u001b]11"
        const val QUERY = "\u001b]11;?\u001b\\"
    }
}
