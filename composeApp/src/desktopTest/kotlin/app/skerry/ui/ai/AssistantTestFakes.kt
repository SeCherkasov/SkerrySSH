package app.skerry.ui.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.FakeClipboard
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.eagerPublishClock
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Answers every request with [reply] in one delta and no suspension. */
internal class OneShotProvider(private val reply: String) : AiProvider {
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flowOf(AiDelta(reply))
    override suspend fun close() {}
}

/** A terminal that never emits and never accepts: the bars under test only read its selection. */
internal class SilentSession : TerminalSession {
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = emptyFlow()
    override suspend fun send(data: ByteArray) = Unit
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = Unit
}

/** One composable under the panel's chrome: theme, fonts, and a clipboard the test can read. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assistantPanel(body: @Composable () -> Unit): FakeClipboard {
    val clipboard = FakeClipboard()
    setContent {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                LocalClipboard provides clipboard,
            ) {
                Box(Modifier.width(PANEL_WIDTH)) { body() }
            }
        }
    }
    return clipboard
}

/**
 * Sweep the mouse across the whole of [text], corner to corner — `centerRight` would stop at the
 * middle line of a block that has more than one.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drag(text: String) {
    onNodeWithText(text).performMouseInput {
        moveTo(topLeft)
        press()
        moveTo(center)
        moveTo(bottomRight)
        release()
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.copy(text: String, clipboard: FakeClipboard): String? {
    onNodeWithText(text).performKeyInput { withKeyDown(COPY_MODIFIER) { pressKey(Key.C) } }
    waitForIdle()
    return clipboard.text
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.selectAndCopy(text: String, clipboard: FakeClipboard): String? {
    drag(text)
    return copy(text, clipboard)
}

/**
 * A terminal AI controller whose provider answers [reply] at once — the dispatcher is unconfined, so
 * `ask`/`explain` have already landed by the time the bar is composed.
 */
internal fun terminalAi(reply: String) = TerminalAiController(
    policy = AiPolicy.Balanced,
    settings = { AiSettings(apiKey = "sk-test") },
    providerFactory = { OneShotProvider(reply) },
    scope = CoroutineScope(Dispatchers.Unconfined),
)

/** The panel's controller, over the same one-shot provider — enough for the panel to compose. */
internal fun sessionAssistant(reply: String = "") = SessionAssistantController(
    policy = AiPolicy.Balanced,
    settings = { AiSettings(apiKey = "sk-test") },
    providerFactory = { OneShotProvider(reply) },
    scope = CoroutineScope(Dispatchers.Unconfined),
)

internal fun terminalState() = TerminalScreenState(SilentSession(), CoroutineScope(Dispatchers.Unconfined), nowMillis = eagerPublishClock())

/** Ctrl everywhere but macOS — what the selection manager listens for. */
internal val COPY_MODIFIER: Key =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft

/** Run/Copy/Edit are not what the selection tests exercise. */
internal val INERT = AssistantCommandActions(run = {}, copy = { _, _ -> }, edit = {}, runnable = false)

/** The panel's width in the tests: the desktop panel, wide enough for the bubble's own cap. */
internal val PANEL_WIDTH = 380.dp
