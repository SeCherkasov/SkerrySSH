package app.skerry.ui.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import app.skerry.shared.host.Host
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runDesktopShell
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HostSidebarVirtualizationTest {
    @Test
    fun `a large folder only composes hosts near the viewport`() = runDesktopShell(withSessions = false) { shell ->
        runOnIdle {
            shell.hosts.importHosts(List(250) { index ->
                Host(
                    id = "virtual-$index", label = "virtual-$index", address = "example.test",
                    username = "test", group = "Large catalog",
                )
            })
        }
        waitForIdle()
        val composed = onAllNodes(
            hasText("virtual-", substring = true) and hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)),
        ).fetchSemanticsNodes().size
        assertTrue(composed in 1..40, "Only viewport rows should be composed; found $composed of 250")
    }
}
