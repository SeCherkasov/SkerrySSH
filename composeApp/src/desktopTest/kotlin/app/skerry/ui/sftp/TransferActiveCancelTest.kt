package app.skerry.ui.sftp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.mobile.MobileTransferCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TransferActiveCancelTest {
    private val active = TransferEntry(7, TransferDirection.Upload, "archive.rpm", 1, 1,
        1024, 1024 * 1024, 1024, 1000, TransferStatus.Active)

    @Test
    fun `desktop active upload has an accessible cancel action`() {
        val cancelled = mutableListOf<Long>()
        runForm({
            Column(Modifier.width(320.dp)) {
                TransferQueueStrip(listOf(active), FontFamily.Monospace) { cancelled += it }
            }
        }) {
            onNodeWithContentDescription("Cancel archive.rpm").assertIsDisplayed().performClick()
        }
        assertEquals(listOf(7L), cancelled)
    }

    @Test
    fun `mobile active upload has a touch sized cancel action`() {
        val cancelled = mutableListOf<Long>()
        runForm({
            Column(Modifier.width(320.dp)) {
                MobileTransferCard(TransferState.Active(active.name, active.direction, 1, 1,
                    active.transferred, active.total), listOf(active), FontFamily.Monospace) { cancelled += it }
            }
        }) {
            val cancel = onNodeWithContentDescription("Cancel archive.rpm").assertIsDisplayed()
            assertTrue(cancel.fetchSemanticsNode().boundsInRoot.height >= 44f)
            cancel.performClick()
        }
        assertEquals(listOf(7L), cancelled)
    }
}
