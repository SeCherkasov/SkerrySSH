package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OSC 52 end to end through a live [TerminalScreen]: the program on the far side asks for text to
 * be put on the clipboard, and the screen has to hand it to the platform's own clipboard rather
 * than to Compose/AWT, which under Wayland writes a buffer nothing pastes from (#282). Only a live
 * screen can show which clipboard the collector reached for.
 */
class TerminalClipboardTest {

    @Test
    fun `an OSC 52 copy goes to the system clipboard`() {
        withTerminal { session, clipboard, wait ->
            session.print(osc52("copied by the host"))
            wait { clipboard.writes.isNotEmpty() }
            assertEquals(listOf("copied by the host"), clipboard.writes)
        }
    }

    /**
     * A clipboard the platform refuses is one refusal, not the end of OSC 52: the collector carrying
     * the copies has to survive it. The write throws by design where the direct path owns the
     * clipboard — falling back to AWT there is the bug (#282), so the throw is the normal case.
     */
    @Test
    fun `a refused copy does not kill the collector`() {
        withTerminal(refuseFirst = true) { session, clipboard, wait ->
            session.print(osc52("refused"))
            wait { clipboard.writes.isNotEmpty() }
            session.print(osc52("taken"))
            wait { clipboard.writes.size == 2 }
            assertEquals(listOf("refused", "taken"), clipboard.writes)
        }
    }
}

/** `ESC ] 52 ; c ; <base64> BEL` — what a program sends to put text on the client's clipboard. */
private fun osc52(text: String): String {
    val payload = Base64.getEncoder().encodeToString(text.toByteArray())
    return "${Char(0x1b)}]52;c;$payload${Char(0x07)}"
}

/** Replays scripted shell output on demand; input and resize are accepted and dropped. */
internal class ScriptedSession : TerminalSession {
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    private val chunks = MutableSharedFlow<ByteArray>(replay = 8, extraBufferCapacity = 64)
    override val output: Flow<ByteArray> = chunks

    fun print(text: String) {
        check(chunks.tryEmit(text.encodeToByteArray()))
    }

    override suspend fun send(data: ByteArray) = Unit
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = Unit
}

/**
 * A live [TerminalScreen] over a scripted session with the OSC 52 gate open and its clipboard
 * replaced by a recorder; [body] gets the session, the recorder, and a frame pump to wait on.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun withTerminal(
    refuseFirst: Boolean = false,
    body: (ScriptedSession, FakeSystemClipboard, (() -> Boolean) -> Unit) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = ScriptedSession()
    val terminal = TerminalScreenState(session, scope, clipboardWriteEnabled = true)
    val clipboard = FakeSystemClipboard(refuseWrites = if (refuseFirst) 1 else 0)
    val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(rememberUiFont(), rememberMono(), rememberMaterialSymbols()),
                LocalSystemClipboard provides clipboard,
            ) {
                Box(Modifier.fillMaxSize().background(Skerry.colors.terminalBg)) {
                    TerminalScreen(terminal, Modifier.fillMaxSize())
                }
            }
        }
    }
    var frame = 0L
    val wait: (() -> Boolean) -> Unit = { done ->
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) {
            scene.render(++frame * 16_000_000L)
            Thread.sleep(16)
        }
    }
    try {
        wait { terminal.cols > 0 }
        body(session, clipboard, wait)
    } finally {
        scene.close()
        scope.cancel()
    }
}
