package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import app.skerry.shared.host.Host
import app.skerry.ui.desktop.runMobileShell
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MobileHostCatalogVirtualizationTest {
    @Test
    fun `a large folder composes viewport rows and can scroll to its last host`() = runMobileShell { shell ->
        runOnIdle {
            shell.hosts.importHosts(List(250) { index ->
                Host("virtual-$index", "virtual-$index", "example.test", 22, "test", group = "Large catalog")
            })
        }
        waitForIdle()
        val composed = onAllNodes(hasText("virtual-", substring = true)).fetchSemanticsNodes().size
        assertTrue(composed in 1..40, "Only viewport rows should be composed; found $composed of 250")
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("virtual-249"))
        onNodeWithText("virtual-249").assertIsDisplayed()
    }
}
