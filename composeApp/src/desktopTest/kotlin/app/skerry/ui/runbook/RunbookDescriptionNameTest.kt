package app.skerry.ui.runbook

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.mobile.RunbookCard
import kotlin.test.Test

/**
 * A runbook's description is the line that says when not to run the thing, and every surface that
 * draws it does so in the same dim grey a screen reader cannot see. Named on each, or it reads as an
 * unlabelled sentence wedged between the runbook's name and its step count.
 *
 * The list row draws it at all because the list searches on it: a hit whose only match is invisible
 * reads as a stray row.
 */
@OptIn(ExperimentalTestApi::class)
class RunbookDescriptionNameTest {

    private val runbook = Runbook(
        id = "rb",
        label = "Rollout",
        description = "Aborts the canary first.",
        steps = listOf(RunbookStep.Command(id = "s1", command = "uptime", confirm = false)),
    )

    @Test
    fun `the desktop card names the description`() {
        runForm({ RunbookRunCard(RunbookEntry(runbook), DesktopDesignState(), {}, {}) }) {
            onNodeWithContentDescription(named, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `the desktop list row names the description`() {
        runForm({ RunbookListRow(RunbookEntry(runbook), selected = false, mono = FontFamily.Monospace) {} }) {
            onNodeWithContentDescription(named, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `the phone's card names the description`() {
        runForm({ RunbookCard(RunbookEntry(runbook), FontFamily.Monospace) {} }) {
            onNodeWithContentDescription(named, useUnmergedTree = true).assertExists()
        }
    }

    private val named get() = string(Res.string.runbook_field_description) + ", " + runbook.description
}
