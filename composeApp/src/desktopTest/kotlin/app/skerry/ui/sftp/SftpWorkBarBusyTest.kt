package app.skerry.ui.sftp

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The panel's refresh button (issue #327, desktop half): it has to say when a pane is listing —
 * keeping its own name, and putting the state in the live region a screen reader hears — without
 * refusing the press, because it drives both panes and the other one may be idle.
 */
@OptIn(ExperimentalTestApi::class)
class SftpWorkBarBusyTest {

    @Test
    fun `an idle panel offers the refresh and fires it`() {
        var refreshed = 0
        runForm({
            // The bar lays its actions out in a row; stacked in the root they would overlap and a
            // click would land on whichever was drawn last.
            Row {
                SftpWorkBarActions(
                    localActive = true,
                    enabled = true,
                    onRefresh = { refreshed++ },
                    onNewFolder = {},
                    onFilter = {},
                    onTransfer = {},
                    busy = false,
                )
            }
        }) {
            onNodeWithContentDescription("Refresh both panes (F9)").assertIsDisplayed().performClick()
            waitForIdle()
        }
        assertEquals(1, refreshed)
    }

    @Test
    fun `a working panel announces the listing and still refreshes`() {
        var refreshed = 0
        runForm({
            // The bar lays its actions out in a row; stacked in the root they would overlap and a
            // click would land on whichever was drawn last.
            Row {
                SftpWorkBarActions(
                    localActive = true,
                    enabled = true,
                    onRefresh = { refreshed++ },
                    onNewFolder = {},
                    onFilter = {},
                    onTransfer = {},
                    busy = true,
                )
            }
        }) {
            onNodeWithContentDescription("Loading…").assertExists()
            onNodeWithContentDescription("Refresh both panes (F9)").assertIsDisplayed().performClick()
            waitForIdle()
        }
        // One pane listing must not veto the other pane's refresh — each drops its own request.
        assertEquals(1, refreshed)
    }
}
