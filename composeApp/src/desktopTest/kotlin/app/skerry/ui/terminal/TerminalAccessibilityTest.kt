package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TerminalAccessibilityTest {
    @Test
    fun `keyboard selects previous output without sending a shell key`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccessibleSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        session.print("first line\r\nsecond line\r\n$ ")
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    Box(Modifier.fillMaxSize()) { TerminalScreen(state, Modifier.fillMaxSize()) }
                }
            }
        }
        waitForIdle()
        onAllNodes(SemanticsMatcher("named terminal output") { node ->
            node.config.contains(SemanticsProperties.ContentDescription) &&
                "Terminal output" in node.config[SemanticsProperties.ContentDescription]
        }).assertCountEquals(1)
        pressTerminalChord(Key.W)
        assertEquals("$", state.selectedText())
        pressTerminalChord(Key.U)
        assertEquals("$", state.selectedText())
        pressTerminalChord(Key.Y)
        assertEquals("second line", state.selectedText())
        assertEquals("", session.sent())
        pressTerminalChord(Key.O)
        waitForIdle()
        assertEquals("\u000f", session.sent())
        scope.cancel()
    }

    @Test
    fun `screen reader selects the last output line without a pointer`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccessibleSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        session.print("first line\r\n/tmp/report.txt\r\n$ ")
        var openedPath: String? = null
        val clipboard = FakeSystemClipboard()

        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    CompositionLocalProvider(LocalSystemClipboard provides clipboard) {
                        Box(Modifier.fillMaxSize()) {
                            TerminalScreen(state, Modifier.fillMaxSize(), imeInput = true, onOpenPath = { openedPath = it })
                        }
                    }
                }
            }
        }

        performTerminalAction("Select last output line")
        assertEquals("/tmp/report.txt", state.selectedText())
        assertEquals("/tmp/report.txt", state.selectedPath())
        waitForIdle()
        val selectedAnnouncement = onAllNodes(SemanticsMatcher("selection announcement") { node ->
            node.config.contains(SemanticsProperties.StateDescription) &&
                node.config[SemanticsProperties.StateDescription].contains("/tmp/report.txt")
        })
        selectedAnnouncement.assertCountEquals(1)
        performTerminalAction("Copy selected text")
        waitForIdle()
        assertEquals(listOf("/tmp/report.txt"), clipboard.writes)
        onAllNodes(SemanticsMatcher("copy success announcement") { node ->
            node.config.contains(SemanticsProperties.ContentDescription) &&
                "Copied selection (copy 1)" in node.config[SemanticsProperties.ContentDescription]
        }).assertCountEquals(1)
        performTerminalAction("Open in Files")
        assertEquals("/tmp/report.txt", openedPath)
        performTerminalAction("Select previous output line")
        assertEquals("first line", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `screen reader copy masks concealed cells`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccessibleSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val clipboard = FakeSystemClipboard()
        session.print("public \u001b[8msecret\u001b[0m done")
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalSystemClipboard provides clipboard,
                ) {
                    Box(Modifier.fillMaxSize()) { TerminalScreen(state, Modifier.fillMaxSize()) }
                }
            }
        }
        performTerminalAction("Select line at cursor")
        waitForIdle()
        val node = onAllNodes(SemanticsMatcher("spoken masked selection") { node ->
            node.config.contains(SemanticsProperties.StateDescription) &&
                node.config[SemanticsProperties.StateDescription].contains("public")
        })[0].fetchSemanticsNode()
        val spoken = node.config[SemanticsProperties.StateDescription]
        assertTrue("secret" !in spoken)
        assertTrue("••••••" in spoken)
        performTerminalAction("Copy selected text")
        waitForIdle()
        assertEquals(listOf("public •••••• done"), clipboard.writes)
        scope.cancel()
    }

    @Test
    fun `screen reader is told when clipboard refuses a copy`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccessibleSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val clipboard = FakeSystemClipboard(refuseWrites = 2)
        session.print("hello")
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalSystemClipboard provides clipboard,
                ) {
                    Box(Modifier.fillMaxSize()) { TerminalScreen(state, Modifier.fillMaxSize()) }
                }
            }
        }
        performTerminalAction("Select line at cursor")
        waitForIdle()
        performTerminalAction("Copy selected text")
        waitForIdle()
        onAllNodes(SemanticsMatcher("copy failure announcement") { node ->
            node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config[SemanticsProperties.ContentDescription]
                    .any { it.startsWith("Could not copy selection") }
        }).assertCountEquals(1)
        performTerminalAction("Copy selected text")
        waitForIdle()
        onAllNodes(SemanticsMatcher("second copy failure announcement") { node ->
            node.config.contains(SemanticsProperties.ContentDescription) &&
                "Could not copy selection (attempt 2)" in node.config[SemanticsProperties.ContentDescription]
        }).assertCountEquals(1)
        scope.cancel()
    }

    @Test
    fun `screen reader can inspect the end of a long selection before copying`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccessibleSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val clipboard = FakeSystemClipboard()
        val output = "a".repeat(180) + " dangerous suffix"
        session.print(output)
        state.beginSelection(app.skerry.shared.terminal.TerminalPos(0, 0))
        state.extendSelection(app.skerry.shared.terminal.TerminalPos(state.cursorRow, state.cursorCol))
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalSystemClipboard provides clipboard,
                ) { Box(Modifier.fillMaxSize()) { TerminalScreen(state, Modifier.fillMaxSize()) } }
            }
        }
        waitForIdle()
        val first = onAllNodes(SemanticsMatcher("first part") { node ->
            node.config.contains(SemanticsProperties.StateDescription) &&
                node.config[SemanticsProperties.StateDescription].contains("more follows")
        })[0].fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("dangerous suffix" !in first)
        performTerminalAction("Read next part of selection")
        waitForIdle()
        val last = onAllNodes(SemanticsMatcher("last part") { node ->
            node.config.contains(SemanticsProperties.StateDescription) &&
                node.config[SemanticsProperties.StateDescription].contains("dangerous suffix")
        })[0].fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("end" in last)
        performTerminalAction("Copy selected text")
        waitForIdle()
        assertEquals(output, clipboard.writes.single().trimEnd())
        scope.cancel()
    }

    @Test
    fun `leading blank output does not hide the copy action`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccessibleSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        session.print("\r\n".repeat(170) + "tail")
        state.beginSelection(app.skerry.shared.terminal.TerminalPos(0, 0))
        state.extendSelection(app.skerry.shared.terminal.TerminalPos(state.cursorRow, state.cursorCol))
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) { Box(Modifier.fillMaxSize()) { TerminalScreen(state, Modifier.fillMaxSize()) } }
            }
        }
        waitForIdle()
        onAllNodes(SemanticsMatcher("copy action after blank rows") { node ->
            node.config.contains(SemanticsActions.CustomActions) &&
                node.config[SemanticsActions.CustomActions].any { it.label == "Copy selected text" }
        }).assertCountEquals(1)
        scope.cancel()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.pressTerminalChord(key: Key) {
    onAllNodes(SemanticsMatcher("terminal selection") {
        it.config.contains(SemanticsActions.CustomActions)
    })[0].performKeyInput {
        keyDown(Key.CtrlLeft)
        keyDown(Key.ShiftLeft)
        keyDown(key)
        keyUp(key)
        keyUp(Key.ShiftLeft)
        keyUp(Key.CtrlLeft)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.performTerminalAction(label: String) {
    val nodes = onAllNodes(SemanticsMatcher(label) { node ->
        node.config.contains(SemanticsActions.CustomActions) &&
            node.config[SemanticsActions.CustomActions].any { it.label == label }
    })
    nodes.assertCountEquals(1)
    val action = nodes[0].fetchSemanticsNode().config[SemanticsActions.CustomActions]
        .first { it.label == label }
    assertTrue(action.action())
}

private class AccessibleSession : TerminalSession {
    private val chunks = MutableSharedFlow<ByteArray>(replay = 1)
    private val sent = StringBuilder()
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = chunks
    fun print(text: String) { check(chunks.tryEmit(text.encodeToByteArray())) }
    fun sent(): String = sent.toString()
    override suspend fun send(data: ByteArray) { sent.append(data.decodeToString()) }
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = Unit
}
