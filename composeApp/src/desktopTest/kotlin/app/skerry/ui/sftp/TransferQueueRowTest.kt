package app.skerry.ui.sftp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.FileTransferFailure
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.files.TransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The queue strip's rows, from a screen reader's side (issue #317): an operation that had to wait
 * for the channel has to be visible, cancellable, and heard — the picker already committed the
 * user's file by the time the app queued it, so a silent row is a file nobody ever gets back.
 */
@OptIn(ExperimentalTestApi::class)
class TransferQueueRowTest {

    private fun entry(id: Long, direction: TransferDirection, name: String, status: TransferStatus) =
        TransferEntry(id, direction, name, 1, 1, 0, 0, 0, 0, status)

    @Test
    fun `waiting work is cancelled, finished work is cleared, and a running transfer is neither`() {
        val dropped = mutableListOf<Long>()
        runForm({
            TransferQueueStrip(
                listOf(
                    entry(1, TransferDirection.Download, "r.log", TransferStatus.Active),
                    entry(2, TransferDirection.Upload, "video.mp4", TransferStatus.Waiting),
                    entry(3, TransferDirection.Upload, "a.txt", TransferStatus.Done),
                ),
                FontFamily.Monospace,
                onDismiss = { dropped += it },
            )
        }) {
            // Named after the row: a queue of identical "Cancel" controls cannot be navigated.
            onNodeWithContentDescription("Clear a.txt").assertIsDisplayed()
            onNodeWithContentDescription("Cancel video.mp4").assertIsDisplayed().performClick()
            waitForIdle()
        }
        // Only the waiting row was pressed, and the running one offered nothing to press.
        assertEquals(listOf(2L), dropped)
    }

    @Test
    fun `the strip says out loud what is waiting and how the last operation ended`() {
        runForm({
            TransferQueueStrip(
                listOf(
                    entry(1, TransferDirection.Upload, "a.txt", TransferStatus.Failed(FileTransferFailure.SessionClosed)),
                    entry(2, TransferDirection.Upload, "picked.txt", TransferStatus.Waiting),
                ),
                FontFamily.Monospace,
                onDismiss = {},
            )
        }) {
            onNodeWithContentDescription(
                "a.txt: Session closed before it started. Waiting for the channel: 1",
            ).assertExists()
        }
    }

    @Test
    fun `an empty queue keeps the live region and says nothing`() {
        // The region has to outlive the strip it describes: a node that appears together with its
        // message is an insertion, and Compose emits no live-region event for one.
        runForm({ TransferQueueStrip(emptyList(), FontFamily.Monospace, onDismiss = {}) }) {
            onNodeWithContentDescription("").assertExists()
        }
    }

    @Test
    fun `a transfer that went through is announced too, not only one that failed`() {
        // The desktop keeps a finished row on the strip, so success is a visible outcome — and one
        // that is only visible is what a live region is for.
        runForm({
            TransferQueueStrip(
                listOf(entry(1, TransferDirection.Download, "r.log", TransferStatus.Done)),
                FontFamily.Monospace,
                onDismiss = {},
            )
        }) {
            onNodeWithContentDescription("r.log: done").assertExists()
        }
    }

    @Test
    fun `a hostile remote name is flattened in the row, its button and what is spoken`() {
        // The name is the far side's text: a bidi override in it makes one queue row read as
        // another file, and a screen reader speak it.
        runForm({
            TransferQueueStrip(
                listOf(entry(1, TransferDirection.Download, SPOOFED, TransferStatus.Waiting)),
                FontFamily.Monospace,
                onDismiss = {},
            )
        }) {
            onNodeWithText(SPOOFED).assertDoesNotExist()
            onNodeWithText(FLATTENED).assertIsDisplayed()
            onNodeWithContentDescription("Cancel $SPOOFED").assertDoesNotExist()
            onNodeWithContentDescription("Cancel $FLATTENED").assertExists()
        }
    }
}

/** Written as an escape, never as the character itself. */
private const val SPOOFED = "report\u202Egpj.exe"

private const val FLATTENED = "reportgpj.exe"
