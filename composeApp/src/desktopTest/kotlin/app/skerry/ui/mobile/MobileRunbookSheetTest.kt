package app.skerry.ui.mobile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onAncestors
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.FakeShellInput
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_field_tags
import app.skerry.ui.generated.resources.lib_snippets_save_snippet
import app.skerry.ui.generated.resources.runbook_delete
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_needs_session
import app.skerry.ui.generated.resources.runbook_run_title
import app.skerry.ui.generated.resources.runbook_run_no_steps
import app.skerry.ui.generated.resources.runbook_step_add
import app.skerry.ui.runbook.RunbookDraft
import app.skerry.ui.snippet.SnippetDraft
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The phone's editors are bottom sheets, and their actions sit under the fields. A runbook with a
 * description and a step or two is already taller than the sheet's ceiling, so without a scroll the
 * sheet clips Run, Save and Delete off the bottom — a procedure that cannot be started from the
 * phone at all, which is how it shipped.
 *
 * The assertions are geometric, not `assertIsDisplayed`: on this backend that check only asks
 * whether a node intersects the scene, which a node clipped by an ancestor still does.
 */
@OptIn(ExperimentalTestApi::class)
class MobileRunbookSheetTest {

    private val runbook = RunbookDraft(
        label = "Disk space check",
        description = "Where the space went.",
        tags = listOf("disk", "ops"),
        steps = listOf(
            RunbookStep.Command(id = "s1", title = "Filesystems", command = "df -h", confirm = false),
            RunbookStep.Command(id = "s2", title = "Biggest directories", command = "du -xh / | sort -h | tail", confirm = false),
        ),
    )

    private val snippet = SnippetDraft(
        label = "Disk space check",
        command = (1..12).joinToString("\n") { "df -h --output=source,size,used,avail,pcent /mnt/vol$it" },
        tags = listOf("disk", "ops", "monitoring", "prod", "staging"),
    )

    @Test
    fun `the runbook sheet keeps its actions inside the panel`() = runMobileShell { shell ->
        shell.runbooks.save(runbook)
        openSheet(shell.state, MobileRoute.Runbooks)

        assertInsidePanel(string(Res.string.runbook_run))
        assertInsidePanel(string(Res.string.runbook_delete))
    }

    /**
     * The other half of the same fix: pinning the actions is only safe because the form they sit
     * above scrolls. Asserted structurally — a form taller than the panel with nothing scrollable
     * inside it is the bug, whatever its last field's bounds happen to be.
     */
    @Test
    fun `the runbook sheet scrolls its form`() = runMobileShell { shell ->
        shell.runbooks.save(runbook)
        openSheet(shell.state, MobileRoute.Runbooks)

        // The scrollable has to be the form's own container inside the panel: the screen behind the
        // sheet scrolls too, and a text field carries a scroll action of its own.
        onNodeWithText(string(Res.string.runbook_step_add), useUnmergedTree = true)
            .onAncestors()
            .filterToOne(hasScrollAction() and hasAnyAncestor(hasTestTag(UiTags.SHEET_PANEL)))
            .assertExists()
    }

    /** Same sheet shape, same ceiling: a long command and a wrapped tag row used to clip Save. */
    @Test
    fun `the snippet sheet keeps its actions inside the panel`() = runMobileShell { shell ->
        shell.snippets.save(snippet)
        openSheet(shell.state, MobileRoute.Snippets)

        assertInsidePanel(string(Res.string.lib_snippets_save_snippet))
        assertInsidePanel(string(Res.string.lib_snippets_delete))
    }

    /** Same mechanism as the runbook sheet's, asserted on its own sheet. */
    @Test
    fun `the snippet sheet scrolls its form`() = runMobileShell { shell ->
        shell.snippets.save(snippet)
        openSheet(shell.state, MobileRoute.Snippets)

        onNodeWithText(string(Res.string.lib_snippets_field_tags), ignoreCase = true, useUnmergedTree = true)
            .onAncestors()
            .filterToOne(hasScrollAction() and hasAnyAncestor(hasTestTag(UiTags.SHEET_PANEL)))
            .assertExists()
    }

    /**
     * The tightest case the sheet has: a landscape phone at the largest system font. The actions are
     * measured before the form, so the form gives way first — but only up to the point where the
     * actions themselves stop fitting, which is what this pins.
     */
    @Test
    fun `the runbook sheet keeps its actions inside a short viewport at a large font scale`() =
        runMobileShell(size = DpSize(844.dp, 390.dp), fontScale = 2f) { shell ->
            shell.runbooks.save(runbook)
            openSheet(shell.state, MobileRoute.Runbooks)

            assertInsidePanel(string(Res.string.runbook_run))
        }

    /** A landscape phone with the keyboard up is the narrowest case the sheet has to survive. */
    @Test
    fun `the runbook sheet keeps its actions inside a short viewport`() =
        runMobileShell(size = DpSize(844.dp, 390.dp)) { shell ->
            shell.runbooks.save(runbook)
            openSheet(shell.state, MobileRoute.Runbooks)

            assertInsidePanel(string(Res.string.runbook_run))
        }

    /** The largest system font the phone offers must not push the actions out of the panel. */
    @Test
    fun `the runbook sheet keeps its actions inside the panel at a large font scale`() =
        runMobileShell(fontScale = 2f) { shell ->
            shell.runbooks.save(runbook)
            openSheet(shell.state, MobileRoute.Runbooks)

            assertInsidePanel(string(Res.string.runbook_run))
            assertInsidePanel(string(Res.string.runbook_delete))
        }

    /** An accepted start is the other side of the refusal: the sheet gives way to the terminal. */
    @Test
    fun `an accepted start closes the sheet and lands on the terminal`() = runMobileShell(withSessions = true) { shell ->
        shell.runbooks.save(runbook)
        openSheet(shell.state, MobileRoute.Runbooks)

        onNodeWithText(string(Res.string.runbook_run)).performClick()
        waitForIdle()

        onNodeWithTag(UiTags.mobileScreen(MobileRoute.Terminal), useUnmergedTree = true).assertExists()
        onNodeWithTag(UiTags.SHEET_PANEL, useUnmergedTree = true).assertDoesNotExist()
        // The run is requested, not started: the confirmation is what sends anything, and until it
        // is answered the session has seen nothing.
        onNodeWithText(string(Res.string.runbook_run_title)).assertIsDisplayed()
        assertTrue(
            FakeShellInput.all().none { it.contains("df -h") },
            "the first step was sent before the confirmation",
        )
    }

    /** Without a connected session there is nothing to run in, and the button has to say so. */
    @Test
    fun `the run button is inert without a session`() = runMobileShell { shell ->
        shell.runbooks.save(runbook)
        openSheet(shell.state, MobileRoute.Runbooks)

        onNodeWithText(string(Res.string.runbook_run)).assertIsNotEnabled()
        onNodeWithText(string(Res.string.runbook_run))
            .assert(hasStateDescription(string(Res.string.runbook_run_needs_session)))
    }

    /**
     * A runbook with no steps is one the runner refuses — it only arrives by sync, since the
     * editor's own Save will not produce one. The button has to say so rather than accept a tap
     * that does nothing.
     */
    @Test
    fun `a runbook with no steps says so instead of offering a run`() = runMobileShell(withSessions = true) { shell ->
        shell.runbooks.save(RunbookDraft(label = "Empty procedure", description = "No steps yet."))
        openSheet(shell.state, MobileRoute.Runbooks, label = "Empty procedure")

        onNodeWithText(string(Res.string.runbook_run)).assertIsNotEnabled()
        // The reason is announced as the button's state: the line under it is drawn for the eye
        // only, so there is no text node of its own to look up.
        onNodeWithText(string(Res.string.runbook_run))
            .assert(hasStateDescription(string(Res.string.runbook_run_no_steps)))
    }

    /**
     * The other half of the disabled Run: a disabled control still carries the click action an
     * accessibility service invokes, and firing it must leave the sheet exactly where it was.
     */
    @Test
    fun `an accessibility click on the disabled run button starts nothing`() = runMobileShell(withSessions = true) { shell ->
        shell.runbooks.save(RunbookDraft(label = "Empty procedure", description = "No steps yet."))
        openSheet(shell.state, MobileRoute.Runbooks, label = "Empty procedure")

        onNodeWithText(string(Res.string.runbook_run)).assertIsNotEnabled()
        onNodeWithText(string(Res.string.runbook_run)).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()

        onNodeWithTag(UiTags.SHEET_PANEL, useUnmergedTree = true).assertExists()
        onNodeWithTag(UiTags.mobileScreen(MobileRoute.Terminal), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun ComposeUiTest.openSheet(state: MobileDesignState, route: MobileRoute, label: String = "Disk space check") {
        state.push(route)
        waitForIdle()
        onNodeWithText(label).performClick()
        waitForIdle()
    }

    /**
     * The panel is the sheet's own box, and an action has to be drawn inside it. The comparison is
     * the whole assertion: the panel's bounds are clipped to what it draws, the action's are not,
     * so an action pushed past the edge reports its real position and fails. The height check is a
     * placement sanity guard, nothing more.
     */
    private fun ComposeUiTest.assertInsidePanel(label: String) {
        val panel = onNodeWithTag(UiTags.SHEET_PANEL, useUnmergedTree = true).getBoundsInRoot()
        // Unclipped, or a half-cut button reads as inside: the panel's clip would clamp the drawn
        // bottom to its own edge.
        val action = onNodeWithText(label).getUnclippedBoundsInRoot()
        assertTrue(
            action.bottom > action.top && action.top >= panel.top && action.bottom <= panel.bottom,
            "\"$label\" is drawn at $action, outside the panel's $panel",
        )
    }
}
