package app.skerry.ui.sftp

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.skerry.shared.files.FileItemType
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_queue_cancel
import app.skerry.ui.generated.resources.shell_tip_files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SftpCancelKeyboardTest {
    @Test
    fun `shift tab and enter cancel from the live file pane`() = cancelWith(Key.Enter)

    @Test
    fun `shift tab and space cancel from the live file pane`() = cancelWith(Key.Spacebar)

    private fun cancelWith(key: Key) = runDesktopShell { shell ->
        onNodeWithContentDescription(string(Res.string.shell_tip_files)).performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("nginx.conf").fetchSemanticsNodes().isNotEmpty()
        }
        val controller = checkNotNull(shell.sessions).activeTerminal!!.focusedPane.controller
        val coordinator = runBlocking { controller.openTransferCoordinator(platformLocalBrowser(), "test") }
        val started = CompletableDeferred<Unit>()
        val discarded = AtomicInteger()
        runOnIdle {
            val item = (coordinator.remote.state as FilePaneState.Loaded).entries.first { it.type == FileItemType.File }
            coordinator.downloadToTarget(item, object : DownloadTarget {
                override val displayName = "keyboard-download"
                override val stagingPath = "/unused-fake-download"
                override suspend fun finalize() { started.complete(Unit); awaitCancellation() }
                override suspend fun discard() { discarded.incrementAndGet() }
            })
        }
        waitUntil(timeoutMillis = 10_000) { started.isCompleted }
        onRoot().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        onNodeWithContentDescription(string(Res.string.sftp_queue_cancel, "keyboard-download")).assertIsFocused()
        onRoot().performKeyInput { pressKey(key) }
        waitUntil(timeoutMillis = 10_000) {
            coordinator.queue.singleOrNull()?.status == TransferStatus.Cancelled && discarded.get() == 1
        }
        assertEquals(1, discarded.get())
        // Once the action disappears, the pane must own keys again without a mouse click.
        onRoot().performKeyInput { pressKey(Key.F7) }
        onNodeWithText(string(Res.string.sftp_new_folder)).assertExists()
    }
}
