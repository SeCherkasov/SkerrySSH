package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.FileTransferFailure
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.sftp.TransferDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone's half of issue #317. The picker on Android has already created the document, or copied
 * the picked file into the app's cache, before the app gets a say — so an operation that has to wait
 * for the channel must be as visible and as cancellable here as it is in the desktop queue strip.
 */
@OptIn(ExperimentalTestApi::class)
class MobileTransferQueueTest {

    private fun entry(id: Long, direction: TransferDirection, name: String, status: TransferStatus) =
        TransferEntry(id, direction, name, 1, 1, 0, 0, 0, 0, status)

    @Test
    fun `an operation waiting behind the running one is a row of its own, and cancellable`() {
        val cancelled = mutableListOf<Long>()
        runForm({
            MobileTransferCard(
                transfer = TransferState.Active("r.log", TransferDirection.Download, 1, 1, 4, 10),
                queue = listOf(
                    entry(1, TransferDirection.Download, "r.log", TransferStatus.Active),
                    entry(2, TransferDirection.Upload, "video.mp4", TransferStatus.Waiting),
                ),
                mono = FontFamily.Monospace,
                onDrop = { cancelled += it },
            )
        }) {
            onNodeWithText("r.log").assertIsDisplayed()
            onNodeWithText("video.mp4").assertIsDisplayed()
            onNodeWithContentDescription("Cancel video.mp4").assertIsDisplayed().performClick()
            waitForIdle()
        }
        assertEquals(listOf(2L), cancelled)
    }

    @Test
    fun `the card says out loud what is still waiting`() {
        runForm({
            MobileTransferCard(
                transfer = TransferState.Idle,
                queue = listOf(entry(1, TransferDirection.Upload, "picked.txt", TransferStatus.Waiting)),
                mono = FontFamily.Monospace,
                onDrop = {},
            )
        }) {
            onNodeWithContentDescription("Waiting for the channel: 1").assertExists()
        }
    }

    @Test
    fun `clearing a failed card drops that row, not every finished one`() {
        val dropped = mutableListOf<Long>()
        runForm({
            MobileTransferCard(
                transfer = TransferState.Failed(7, "picked.txt", FileTransferFailure.SessionClosed),
                queue = listOf(
                    entry(6, TransferDirection.Upload, "earlier.txt", TransferStatus.Done),
                    entry(7, TransferDirection.Upload, "picked.txt", TransferStatus.Failed(FileTransferFailure.SessionClosed)),
                ),
                mono = FontFamily.Monospace,
                onDrop = { dropped += it },
            )
        }) {
            // The label names one file, so the button must act on that one row.
            onNodeWithContentDescription("Clear picked.txt").assertIsDisplayed().performClick()
            waitForIdle()
        }
        assertEquals(listOf(7L), dropped)
    }
}
