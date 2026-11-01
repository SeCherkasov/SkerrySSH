package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.terminal.FailedCommandSnapshot
import app.skerry.ui.ai.FailedCommandExplanationController
import app.skerry.ui.desktop.runForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FailedCommandExplanationDialogTest {
    @Test fun previewRequiresSendAndUsesExactlyTheReviewedMessages() {
        val requests = mutableListOf<AiChatRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { AiSettings(apiKey = "synthetic-key") }, scope = scope,
            providerFactory = { object : AiProvider {
                override fun chat(request: AiChatRequest) = flow {
                    requests += request
                    emit(AiDelta("Synthetic explanation."))
                }
                override suspend fun close() = Unit
            } },
        )
        controller.prepare(FailedCommandSnapshot("false", "PASSWORD=synthetic-secret", 1, false))
        val preview = checkNotNull(controller.preview)
        try {
            runForm({ ExplainFailedCommandDialog(controller, terminal = null, onDismiss = controller::cancel) }) {
                onNodeWithText(preview.messages.last().content).assertExists()
                assertTrue(requests.isEmpty())
                System.getenv("SKERRY_404_UI_CAPTURE")?.let { directory ->
                    val file = Path.of(directory, "failed-command-preview.png")
                    Files.createDirectories(file.parent)
                    Image.makeFromBitmap(onNode(isPopup()).captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG).use { data -> Files.write(file, checkNotNull(data).bytes) }
                    }
                }
                onNodeWithText("Send request").performClick()
                waitForIdle()
                onNodeWithText("Synthetic explanation.").assertExists()
                assertEquals(preview.messages, requests.single().messages)
            }
        } finally { controller.cancel(); scope.cancel() }
    }

    @Test fun runningASuggestionHandsOffToTheProductionGuardAndClosesThePopup() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val terminal = app.skerry.ui.ai.terminalState()
        terminal.guardPolicy = app.skerry.shared.guard.ProductionGuardPolicy(production = true, confirmWarnings = true)
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { AiSettings(apiKey = "synthetic-key") }, scope = scope,
            providerFactory = { app.skerry.ui.ai.OneShotProvider("Try this:\n```sh\nsudo systemctl status demo\n```") },
        )
        controller.prepare(FailedCommandSnapshot("false", "failure", 1, false))
        val shown = mutableStateOf(true)
        var restoredOrigin = false
        try {
            runForm({
                if (shown.value) ExplainFailedCommandDialog(controller, terminal, onDismiss = { shown.value = false },
                    onBeforeRun = { restoredOrigin = true })
            }) {
                onNodeWithText("Send request").performClick()
                waitForIdle()
                assertEquals(null, terminal.pendingGuarded)
                onNodeWithText("Run in session").performClick()
                waitForIdle()
                assertTrue(restoredOrigin)
                assertEquals("sudo systemctl status demo", terminal.pendingGuardedQuote)
                assertTrue(!shown.value)
                onNode(isPopup()).assertDoesNotExist()
            }
        } finally { controller.cancel(); scope.cancel() }
    }

    @Test fun longPressLowerHalfOfFailedMarkerKeepsTheSelectedCommand() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ScriptedSession()
        val terminal = TerminalScreenState(session, scope)
        val esc = Char(0x1b)
        val bel = Char(0x07)
        val failure = "$esc]133;A$bel$ $esc]133;B${bel}false$esc]133;C$bel$esc]133;D;1$bel"
        session.print("$failure\r\n$failure")
        var selected: app.skerry.shared.terminal.ShellCommandMark? = null
        try {
            runForm({
                Box(Modifier.size(100.dp)) {
                    CommandMarkGutter(terminal, cellHeightPx = 40f, scrollPx = { 0f },
                        colors = MarkColors(Color.Green, Color.Red, Color.Gray), width = 20.dp, topInset = 0.dp,
                        modifier = Modifier.testTag("failed-command-gutter"), onExplain = { selected = it })
                }
            }) {
                waitUntil("both failed markers appeared") { terminal.commandMarks.size == 2 }
                val first = terminal.commandMarks.first()
                assertEquals(listOf(0, 1), terminal.commandMarks.map { it.promptRow })
                // A square fills y=6.8..33.2; y=28 is inside the first marker, not the next row.
                onNodeWithTag("failed-command-gutter").performTouchInput { longClick(Offset(center.x, 28f)) }
                waitForIdle()
                assertEquals(first, selected)
            }
        } finally { scope.cancel() }
    }

    @Test fun phoneWidthPreviewKeepsTheSendAndCancelActionsVisible() {
        app.skerry.ui.desktop.runDesktopShell(windowWidth = 390) { shell ->
            val session = checkNotNull(shell.sessions?.active?.focusedPane)
            val terminal = (session.controller.uiState as app.skerry.ui.connection.ConnectionUiState.Connected).terminal
            runOnIdle { shell.state.failedCommandTarget = FailedCommandTarget(session, terminal, FailedCommandSnapshot("false", "failure", 1, false)) }
            waitForIdle()
            onNodeWithText("Send request").assertExists()
            onNodeWithText("Cancel").assertExists()
            val send = onNodeWithText("Send request").fetchSemanticsNode().boundsInRoot
            val cancel = onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
            assertTrue(send.left >= 0 && send.right <= 390 && send.top >= 0 && send.bottom <= 768, "$send")
            assertTrue(cancel.left >= 0 && cancel.right <= 390 && cancel.top >= 0 && cancel.bottom <= 768, "$cancel")
            System.getenv("SKERRY_404_UI_CAPTURE")?.let { directory ->
                val file = Path.of(directory, "failed-command-phone-width.png")
                Files.createDirectories(file.parent)
                Image.makeFromBitmap(onNode(isPopup()).captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG).use { data -> Files.write(file, checkNotNull(data).bytes) }
                }
            }
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertEquals(null, shell.state.failedCommandTarget)
        }
    }

    @Test fun cancellingThePreviewNeverConstructsAProvider() {
        var built = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FailedCommandExplanationController(
            policy = { AiPolicy.Balanced }, settings = { AiSettings(apiKey = "synthetic-key") }, scope = scope,
            providerFactory = { built = true; error("Cancel must not reach a provider") },
        )
        controller.prepare(FailedCommandSnapshot("false", "failure", 1, false))
        val shown = mutableStateOf(true)
        try {
            runForm({ if (shown.value) ExplainFailedCommandDialog(controller, null, onDismiss = { controller.cancel(); shown.value = false }) }) {
                onNodeWithText("Cancel").performClick()
                waitForIdle()
                assertTrue(!built)
                assertEquals(null, controller.preview)
            }
        } finally { controller.cancel(); scope.cancel() }
    }
}
