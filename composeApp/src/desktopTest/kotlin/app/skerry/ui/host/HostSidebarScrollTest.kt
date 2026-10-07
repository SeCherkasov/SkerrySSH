package app.skerry.ui.host

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import app.skerry.shared.host.Host
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.runDesktopShell
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HostSidebarScrollTest {
    @Test
    fun `scrolling reaches the end of a large folder and search still finds its start`() =
        runDesktopShell(withSessions = false) { shell ->
            seedLargeFolder(shell)
            scrollCatalogTo("large-249")
            onCatalog("large-249").assertIsDisplayed()
            onCatalog("large-0").assertDoesNotExist()
            runOnIdle { shell.state.onHostSearch("large-0") }
            waitForIdle()
            onNode(hasText("large-0") and !hasSetTextAction() and hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)))
                .assertIsDisplayed()
        }

    @Test
    fun `dragging after a deep scroll preserves the index of uncomposed hosts`() =
        runDesktopShell(withSessions = false) { shell ->
            seedLargeFolder(shell)
            scrollCatalogTo("large-180")
            dragRow("large-180", "large-185")
            val expected = (0..249).toMutableList().apply { remove(180); add(indexOf(185) + 1, 180) }
            assertEquals(expected.map { "large-$it" }, shell.hosts.hosts.filter { it.group == LARGE }.map { it.label })
        }

    @Test
    fun `folder reordering after a deep scroll counts headers outside the viewport`() =
        runDesktopShell(withSessions = false) { shell ->
            runOnIdle {
                shell.hosts.importHosts(List(60) { index ->
                    Host("folder-$index", "row-$index", "example.test", username = "test", group = "Folder $index")
                })
                repeat(60) { shell.state.toggleGroupCollapsed("Folder $it") }
            }
            waitForIdle()
            scrollCatalogTo("Folder 35")
            dragRow("Folder 35", "Folder 37")
            val expected = (0..59).toMutableList().apply { remove(35); add(indexOf(37) + 1, 35) }
            assertEquals(
                expected.map { "Folder $it" },
                shell.hosts.hosts.mapNotNull { it.group }.distinct().filter { it.startsWith("Folder ") },
            )
        }

    @Test
    fun `a host can move between folders when their headers are outside the viewport`() =
        runDesktopShell(withSessions = false) { shell ->
            seedLargeFolder(shell)
            runOnIdle {
                shell.hosts.importHosts(List(2) { index ->
                    Host("destination-$index", "destination-$index", "example.test", username = "test", group = "Destination")
                })
            }
            waitForIdle()
            scrollCatalogTo("large-248")
            dragRow("large-248", "destination-1")
            assertEquals(
                listOf("destination-0", "destination-1", "large-248"),
                shell.hosts.hosts.filter { it.group == "Destination" }.map { it.label },
            )
            assertEquals(249, shell.hosts.hosts.count { it.group == LARGE })
        }

    @Test
    fun `scrolling during a drag retains the gesture after the source leaves the viewport`() =
        runDesktopShell(withSessions = false) { shell ->
            seedLargeFolder(shell)
            scrollCatalogTo("large-180")
            onCatalog("large-180").performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(0f, 20f))
            }
            waitForIdle()
            scrollCatalogTo("large-223")
            val target = onCatalog("large-223").fetchSemanticsNode().boundsInRoot.center + Offset(0f, 12f)
            onRoot().performMouseInput {
                moveTo(target)
                release()
            }
            waitForIdle()
            val expected = (0..249).toMutableList().apply { remove(180); add(indexOf(223) + 1, 180) }
            assertEquals(expected.map { "large-$it" }, shell.hosts.hosts.filter { it.group == LARGE }.map { it.label })
        }

    private fun ComposeUiTest.seedLargeFolder(shell: DesktopShell) {
        runOnIdle {
            shell.hosts.importHosts(List(250) { index ->
                Host("large-$index", "large-$index", "example.test", username = "test", group = LARGE)
            })
        }
        waitForIdle()
    }

    private fun ComposeUiTest.scrollCatalogTo(label: String) {
        onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)))
            .performScrollToNode(hasText(label))
        waitForIdle()
    }

    private fun ComposeUiTest.dragRow(from: String, to: String) {
        val start = onCatalog(from).fetchSemanticsNode().boundsInRoot.center.y
        val target = onCatalog(to).fetchSemanticsNode().boundsInRoot.center.y + 12f
        onCatalog(from).performMouseInput {
            moveTo(center)
            press()
            repeat(6) { moveBy(Offset(0f, (target - start) / 6)) }
            release()
        }
        waitForIdle()
    }
}

private const val LARGE = "Large catalog"
