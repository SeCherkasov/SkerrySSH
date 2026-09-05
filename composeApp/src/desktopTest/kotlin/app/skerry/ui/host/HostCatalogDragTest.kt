package app.skerry.ui.host

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performMouseInput
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.runDesktopShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Manual sort in the sidebar: dragging a row to another folder, within its own, and dragging a
 * folder header past its neighbour.
 *
 * The arithmetic is already covered ([HostReorderingTest], [app.skerry.ui.design.FolderDropTargetingTest]) and so is the
 * gesture's dead zone ([app.skerry.ui.terminal.HostRowClickJitterTest]). What neither reaches is the
 * chain in between: row geometry recorded on layout, a drop target computed from it, and a
 * controller call that writes the new order to the catalog. A drag that lands on the wrong index
 * quietly refiles someone's production host.
 */
@OptIn(ExperimentalTestApi::class)
class HostCatalogDragTest {

    @Test
    fun `dragging a row onto another folder refiles it`() = runDesktopShell { shell ->
        dragCatalogRow(from = PI_HOST, to = DB_HOST)
        assertEquals(
            PROD_GROUP,
            shell.host(PI_HOST).group,
            "a row dropped among the Production rows belongs to Production",
        )
    }

    @Test
    fun `dragging a row past its neighbour reorders the folder`() = runDesktopShell { shell ->
        assertEquals(listOf(WEB_HOST, DB_HOST), shell.groupOrder(PROD_GROUP))

        dragCatalogRow(from = WEB_HOST, to = DB_HOST, overshootPx = ROW_OVERSHOOT_PX)
        assertEquals(
            listOf(DB_HOST, WEB_HOST),
            shell.groupOrder(PROD_GROUP),
            "dropping a row below its neighbour must swap the two",
        )
    }

    /**
     * The no-group bucket is a drop target like any folder, and the one that clears [Host.group]
     * rather than setting it — dragging a host out of a folder is how a group is left.
     */
    @Test
    fun `dragging a row into the no-group bucket clears its group`() = runDesktopShell { shell ->
        dragCatalogRow(from = DB_HOST, to = UNGROUPED_HOST)
        assertNull(shell.host(DB_HOST).group, "a row dropped among the ungrouped rows has no group")
    }

    @Test
    fun `dragging a folder header past its neighbour reorders the folders`() = runDesktopShell { shell ->
        assertEquals(listOf(PROD_GROUP, HOMELAB_GROUP), shell.folderOrder())

        dragCatalogRow(from = HOMELAB_GROUP, to = PROD_GROUP, overshootPx = ROW_OVERSHOOT_PX)
        assertEquals(
            listOf(HOMELAB_GROUP, PROD_GROUP),
            shell.folderOrder(),
            "a folder dropped above its neighbour must come first",
        )
    }

    /**
     * Drags the sidebar node drawing [from] onto the one drawing [to], in steps, past the mouse dead
     * zone ([app.skerry.ui.host.detectDeadZoneDragGestures]). [overshootPx] pushes the pointer beyond
     * the target's center, which is what decides whether the row lands before it or after.
     */
    private fun ComposeUiTest.dragCatalogRow(from: String, to: String, overshootPx: Float = 0f) {
        val start = onCatalog(from).centerY()
        val targetCenter = onCatalog(to).centerY()
        // Overshoot follows the direction of travel: past the target from above, past it from below.
        val target = targetCenter + if (targetCenter > start) overshootPx else -overshootPx
        onCatalog(from).performMouseInput {
            moveTo(center)
            press()
            // In steps, like a hand: the gesture claims the pointer on the first move past the dead
            // zone and tracks every one after it.
            val distance = target - start
            repeat(DRAG_STEPS) { moveBy(Offset(0f, distance / DRAG_STEPS)) }
            release()
        }
        waitForIdle()
    }

    private fun SemanticsNodeInteraction.centerY(): Float = fetchSemanticsNode().boundsInRoot.center.y
}

/** Enough steps that each one stays small, as a real drag's moves are. */
private const val DRAG_STEPS = 6

/** Past the target row's center, so the drop lands on its far side rather than in front of it. */
private const val ROW_OVERSHOOT_PX = 12f

private fun DesktopShell.host(label: String) = hosts.hosts.first { it.label == label }

/** Labels of one folder's hosts, in catalog order. */
private fun DesktopShell.groupOrder(group: String): List<String> =
    hosts.hosts.filter { it.group == group }.map { it.label }

/** Folder names in catalog order, ungrouped hosts aside. */
private fun DesktopShell.folderOrder(): List<String> = hosts.hosts.mapNotNull { it.group }.distinct()

// Seeded catalog ([app.skerry.ui.desktop.seededHosts]).
private const val PROD_GROUP = "Production"
private const val HOMELAB_GROUP = "Homelab"
private const val WEB_HOST = "prod-web-01"
private const val DB_HOST = "db-master"
private const val PI_HOST = "homelab-pi"
private const val UNGROUPED_HOST = "vps-edge"
