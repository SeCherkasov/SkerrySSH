package app.skerry.ui.snippet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.FakeShellInput
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_run
import kotlin.test.Test

/**
 * The choice-parameter path end to end: `${{name:a|b|c}}` renders a picker in the run
 * confirmation, the picked option — not the default — is what reaches the session. The parsing
 * and seeding are unit-tested; what only this can catch is the wiring from a click on an option
 * to the line the terminal is sent.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetChoiceRunTest {

    @Test
    fun `the picked option lands in the sent command line`() = runDesktopShell { shell ->
        onNodeWithTag(UiTags.railView(DesktopView.Snippets)).performClick()
        waitForIdle()
        shell.snippets.save(
            SnippetDraft(label = "Restart service", command = "sudo systemctl restart \${{env:dev|staging|prod}}"),
        )
        waitForIdle()
        FakeShellInput.clear()

        // The saved snippet is the library's only row and comes pre-selected; Run opens the
        // confirmation with the picker seeded on the default option.
        onNodeWithText(string(Res.string.lib_snippets_run)).performClick()
        waitForIdle()
        onNodeWithText("dev").assertIsDisplayed()

        // Pick a different option; the trigger takes it over.
        onNodeWithText("dev").performClick()
        waitForIdle()
        onAllNodesWithText("staging").onFirst().performClick()
        waitForIdle()

        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitUntil { FakeShellInput.all().any { it.contains("sudo systemctl restart staging") } }
    }
}
