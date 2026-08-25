package app.skerry.ui.mobile

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #327: on the phone a file the user deleted stayed on screen, and there was nothing to
 * press to ask for the listing again — the desktop panel has F9 and a refresh button, the phone
 * had neither, so the only way out was to leave the directory and come back.
 */
@OptIn(ExperimentalTestApi::class)
class MobileFilesRefreshTest {

    /** The whole context-menu path, the one the report describes: long-press → Delete → confirm. */
    @Test
    fun `deleting a file from the context menu drops its row`() {
        val fake = FakeSftpClient(startDir = "/srv").apply {
            seedDir("/srv")
            seedFile("/srv/alpha.txt")
            seedFile("/srv/beta.txt")
        }
        // Unconfined: the source answers in place, so the listing is settled by waitForIdle and the
        // test never waits on wall-clock time — the one call it holds is held explicitly.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val pane = FilePaneController(SftpFileBrowser(fake, label = "stub"), scope)
        pane.start()
        try {
            runForm({
                MobileLivePane(
                    pane = pane,
                    mono = FontFamily.Monospace,
                    onTransfer = {},
                    onDownloadHere = null,
                    onOpenEditor = { _, _ -> },
                    modifier = Modifier,
                )
            }) {
                onNodeWithText("alpha.txt").performTouchInput { longClick() }
                waitForIdle()
                onNodeWithText("Delete").performClick()
                waitForIdle()
                onNodeWithText("Delete file?").assertExists()

                // Hold the relist that follows the delete: the row has to go when the source
                // confirms it is gone, not a round trip later. Waiting for the relist is exactly
                // what the report read as "the delete did nothing".
                val relist = CompletableDeferred<Unit>()
                fake.listGate = relist
                onNodeWithText("Delete").performClick()
                waitForIdle()

                onNodeWithText("alpha.txt").assertDoesNotExist()
                onNodeWithText("beta.txt").assertExists()

                fake.listGate = null
                relist.complete(Unit)
                waitForIdle()
                onNodeWithText("alpha.txt").assertDoesNotExist()
                onNodeWithText("beta.txt").assertExists()
            }
        } finally {
            scope.cancel()
        }
    }

    /** The breadcrumb row carries the refresh the phone was missing. */
    @Test
    fun `the breadcrumb row offers a refresh`() {
        var refreshed = 0
        runForm({
            MobileFilesBreadcrumbRow(
                label = "prod-web-01",
                path = "/srv",
                mono = FontFamily.Monospace,
                onGoToPath = {},
                onRefresh = { refreshed++ },
                busy = false,
            )
        }) {
            onNodeWithContentDescription("Refresh")
                .assertIsDisplayed()
                // A finger, not a mouse: the glyph is 18sp and the funnel sits 6dp away, so the
                // press has to land on a button-sized box rather than on the glyph itself.
                .assertWidthIsAtLeast(32.dp)
                .assertHeightIsAtLeast(32.dp)
                .performClick()
            waitForIdle()
        }
        assertEquals(1, refreshed)
    }

    /**
     * While the pane is working the row announces it and the control does not fire: a second
     * request would be dropped by the pane's own serialization, and a button that looks live but
     * does nothing is exactly what the report read as a broken refresh.
     */
    @Test
    fun `while the pane works the row announces it and the refresh stays inert`() {
        var refreshed = 0
        runForm({
            MobileFilesBreadcrumbRow(
                label = "prod-web-01",
                path = "/srv",
                mono = FontFamily.Monospace,
                onGoToPath = {},
                onRefresh = { refreshed++ },
                busy = true,
            )
        }) {
            onNodeWithContentDescription("Loading…").assertExists()
            onNodeWithContentDescription("Refresh").assertIsDisplayed().assertIsNotEnabled().performClick()
            waitForIdle()
        }
        assertEquals(0, refreshed)
    }

    /**
     * The wiring itself, at the seam the one live call site uses: the button has to reach the
     * pane's own refresh, and the state it reports has to be the pane's own. Asserting the two
     * literals at the breadcrumb row proves neither.
     */
    @Test
    fun `the live breadcrumb asks its own pane for the listing and reports its state`() {
        val fake = FakeSftpClient(startDir = "/srv").apply {
            seedDir("/srv")
            seedFile("/srv/alpha.txt")
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val pane = FilePaneController(SftpFileBrowser(fake, label = "prod-web-01"), scope)
        pane.start()
        try {
            runForm({
                MobileLiveBreadcrumb(pane, FontFamily.Monospace, filterOpen = false, onFilterOpenChange = {})
            }) {
                onNodeWithContentDescription("Loading…").assertDoesNotExist()

                // Held before the press, so the listing the press asks for is still in flight when
                // the row is next drawn.
                val relist = CompletableDeferred<Unit>()
                fake.listGate = relist
                onNodeWithContentDescription("Refresh").performClick()
                waitForIdle()
                onNodeWithContentDescription("Loading…").assertExists()

                fake.listGate = null
                relist.complete(Unit)
                waitForIdle()
                onNodeWithContentDescription("Loading…").assertDoesNotExist()
            }
        } finally {
            scope.cancel()
        }
    }
}
