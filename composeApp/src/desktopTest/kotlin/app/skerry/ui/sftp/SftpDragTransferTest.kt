package app.skerry.ui.sftp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.pressKey
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_cancel
import app.skerry.ui.generated.resources.sftp_copy_to_local_q
import app.skerry.ui.generated.resources.sftp_items_count
import app.skerry.ui.generated.resources.sftp_move_to_local_q
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_pane_local
import app.skerry.ui.generated.resources.sftp_what_single
import app.skerry.ui.generated.resources.shell_tip_files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dragging a row from one pane of the file panel onto the other. The state behind it is covered by
 * [FileDragStateTest]; what only the real shell shows is whether the press on a row turns into a
 * drag at all, whether the pane it is released over is found, and whether Shift reaches the drop.
 *
 * The remote pane is the fake SFTP client's canned listing; the drop lands on the local pane's
 * header badge, which is the one part of that pane whose content does not depend on the machine.
 */
@OptIn(ExperimentalTestApi::class)
class SftpDragTransferTest {

    @Test
    fun `dropping a remote row on the local pane asks to copy it there`() = runDesktopShell {
        openFiles()
        dragRemoteFileTo(localPaneOffset())

        onNodeWithText(string(Res.string.sftp_copy_to_local_q)).assertIsDisplayed()
    }

    @Test
    fun `holding shift through the drop asks to move instead`() = runDesktopShell {
        openFiles()
        val target = localPaneOffset()
        onNodeWithText(REMOTE_FILE).performMultiModalInput {
            key { keyDown(Key.ShiftLeft) }
            mouse { dragTo(target) }
            key { keyUp(Key.ShiftLeft) }
        }
        waitForIdle()

        onNodeWithText(string(Res.string.sftp_move_to_local_q)).assertIsDisplayed()
    }

    @Test
    fun `a drag released back over its own pane asks nothing`() = runDesktopShell {
        openFiles()
        dragRemoteFileTo(Offset(0f, 60f))

        assertNoTransferQuestion()
    }

    @Test
    fun `escape during the drag drops it on the floor, and the next drag still works`() = runDesktopShell {
        openFiles()
        val target = localPaneOffset()
        onNodeWithText(REMOTE_FILE).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-DEAD_ZONE_CLEARANCE, 0f))
            moveTo(target)
        }
        onNodeWithText(REMOTE_FILE).performKeyInput { pressKey(Key.Escape) }
        onNodeWithText(REMOTE_FILE).performMouseInput { release() }
        waitForIdle()

        assertNoTransferQuestion()

        dragRemoteFileTo(localPaneOffset())
        onNodeWithText(string(Res.string.sftp_copy_to_local_q)).assertIsDisplayed()
    }

    /** The secondary button paints marks across rows; carried over the other pane it is still that. */
    @Test
    fun `a right-button drag onto the other pane only marks rows`() = runDesktopShell {
        openFiles()
        val target = localPaneOffset()
        onNodeWithText(REMOTE_FILE).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            moveBy(Offset(-DEAD_ZONE_CLEARANCE, 0f))
            moveTo(target)
            release(MouseButton.Secondary)
        }
        waitForIdle()

        assertNoTransferQuestion()
    }

    /** A marked row carries every marked row with it, not just itself. */
    @Test
    fun `dragging a marked row carries the whole marked set`() = runDesktopShell {
        openFiles()
        onNodeWithText(REMOTE_FILE).performClick()
        onNodeWithText(REMOTE_FILE).performKeyInput {
            pressKey(Key.Insert)
            pressKey(Key.Insert)
        }
        waitForIdle()
        dragRemoteFileTo(localPaneOffset())

        onNodeWithText(string(Res.string.sftp_items_count, 2), substring = true).assertIsDisplayed()
    }

    /** An unmarked row carries itself alone, whatever else is marked in the pane. */
    @Test
    fun `dragging an unmarked row carries only that row`() = runDesktopShell {
        openFiles()
        markNginxAndRobots()
        dragRowTo(UNMARKED_FILE, localPaneOffset(UNMARKED_FILE))

        onNodeWithText(string(Res.string.sftp_copy_to_local_q)).assertIsDisplayed()
        onNodeWithText(string(Res.string.sftp_what_single, UNMARKED_FILE), substring = true).assertIsDisplayed()
    }

    /** A drag that lands nowhere leaves the marks as they were: starting one is not a click that re-marks. */
    @Test
    fun `an abandoned drag of an unmarked row keeps the earlier marks`() = runDesktopShell {
        openFiles()
        markNginxAndRobots()
        dragRowTo(UNMARKED_FILE, Offset(0f, 60f))
        dragRemoteFileTo(localPaneOffset())

        onNodeWithText(string(Res.string.sftp_items_count, 2), substring = true).assertIsDisplayed()
    }

    /** Cancelling the question a drop raised leaves the marks the drop replaced, as a cancelled F5 does. */
    @Test
    fun `cancelling the drop of an unmarked row restores the earlier marks`() = runDesktopShell {
        openFiles()
        markNginxAndRobots()
        dragRowTo(UNMARKED_FILE, localPaneOffset(UNMARKED_FILE))
        onNodeWithText(string(Res.string.sftp_cancel)).performClick()
        waitForIdle()
        dragRemoteFileTo(localPaneOffset())

        onNodeWithText(string(Res.string.sftp_items_count, 2), substring = true).assertIsDisplayed()
    }

    /** The panel's keys wait until the drag is over: F7 mid-drag opens no folder dialog. */
    @Test
    fun `panel keys do nothing while a drag is held`() = runDesktopShell {
        openFiles()
        onNodeWithText(REMOTE_FILE).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-DEAD_ZONE_CLEARANCE, 0f))
        }
        onNodeWithText(REMOTE_FILE).performKeyInput { pressKey(Key.F7) }
        onNodeWithText(REMOTE_FILE).performMouseInput { release() }
        waitForIdle()

        assertTrue(onAllNodesWithText(string(Res.string.sftp_new_folder)).fetchSemanticsNodes().isEmpty())
    }

    /** Escape held into auto-repeat cancels the drag once; the repeats must not clear the marks. */
    @Test
    fun `holding escape to cancel a drag keeps the marks`() = runDesktopShell {
        openFiles()
        markNginxAndRobots()
        onNodeWithText(REMOTE_FILE).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-DEAD_ZONE_CLEARANCE, 0f))
        }
        onNodeWithText(REMOTE_FILE).performKeyInput {
            keyDown(Key.Escape)
            advanceEventTime(ESCAPE_HOLD_MS)
            keyUp(Key.Escape)
        }
        onNodeWithText(REMOTE_FILE).performMouseInput { release() }
        waitForIdle()
        dragRemoteFileTo(localPaneOffset())

        onNodeWithText(string(Res.string.sftp_items_count, 2), substring = true).assertIsDisplayed()
    }

    /** The drop path and the keyboard path share one confirmation; F5 still reaches it. */
    @Test
    fun `f5 still asks to copy the cursored row`() = runDesktopShell {
        openFiles()
        onNodeWithText(REMOTE_FILE).performClick()
        onNodeWithText(REMOTE_FILE).performKeyInput { pressKey(Key.F5) }
        waitForIdle()

        onNodeWithText(string(Res.string.sftp_copy_to_local_q)).assertIsDisplayed()
    }

    @Test
    fun `f6 still asks to move the cursored row`() = runDesktopShell {
        openFiles()
        onNodeWithText(REMOTE_FILE).performClick()
        onNodeWithText(REMOTE_FILE).performKeyInput { pressKey(Key.F6) }
        waitForIdle()

        onNodeWithText(string(Res.string.sftp_move_to_local_q)).assertIsDisplayed()
    }

    /** Click nginx.conf, then Insert twice: nginx.conf and the row after it (robots.txt) are marked. */
    private fun ComposeUiTest.markNginxAndRobots() {
        onNodeWithText(REMOTE_FILE).performClick()
        onNodeWithText(REMOTE_FILE).performKeyInput {
            pressKey(Key.Insert)
            pressKey(Key.Insert)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertNoTransferQuestion() {
        val asked = onAllNodesWithText(string(Res.string.sftp_copy_to_local_q)).fetchSemanticsNodes() +
            onAllNodesWithText(string(Res.string.sftp_move_to_local_q)).fetchSemanticsNodes()
        assertTrue(asked.isEmpty(), "no transfer question expected")
    }

    private fun ComposeUiTest.dragRemoteFileTo(target: Offset) = dragRowTo(REMOTE_FILE, target)

    private fun ComposeUiTest.dragRowTo(name: String, target: Offset) {
        onNodeWithText(name).performMouseInput { dragTo(target) }
        waitForIdle()
    }

    /** Where the local pane's badge sits, relative to the top-left corner of remote row [name]. */
    private fun ComposeUiTest.localPaneOffset(name: String = REMOTE_FILE): Offset {
        val row = onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val badge = onNodeWithText(localLabel()).fetchSemanticsNode().boundsInRoot
        return badge.center - row.topLeft
    }

    private fun ComposeUiTest.openFiles() {
        onNodeWithContentDescription(string(Res.string.shell_tip_files)).performClick()
        waitUntil("remote listing shows $REMOTE_FILE", timeoutMillis = 10_000) {
            onAllNodesWithText(REMOTE_FILE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun localLabel(): String = string(Res.string.sftp_pane_local)
}

/** Press, clear the drag dead zone, travel to [target] in a few steps (a real hand does), release. */
@OptIn(ExperimentalTestApi::class)
private fun MouseInjectionScope.dragTo(target: Offset) {
    moveTo(center)
    press()
    moveBy(Offset(-DEAD_ZONE_CLEARANCE, 0f))
    val from = currentPosition
    for (step in 1..DRAG_STEPS) moveTo(from + (target - from) * (step.toFloat() / DRAG_STEPS))
    release()
}

/** Comfortably past the 6dp mouse dead zone at any test density. */
private const val DEAD_ZONE_CLEARANCE = 30f

private const val DRAG_STEPS = 5

// Rows of the fake client's canned listing.
private const val REMOTE_FILE = "nginx.conf"
private const val UNMARKED_FILE = "deploy.sh"

/** Past the desktop key-repeat delay, so a held key sends repeats. */
private const val ESCAPE_HOLD_MS = 1_000L
