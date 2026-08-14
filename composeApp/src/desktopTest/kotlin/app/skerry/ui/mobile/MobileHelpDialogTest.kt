package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_add
import app.skerry.ui.generated.resources.help_added
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.help_close
import app.skerry.ui.snippet.SNIPPET_HELP_EXAMPLES
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone's help dialog: same composable as the desktop one, but reached through
 * [MobilePushHeader]'s actions slot — the wiring this test exists for.
 */
@OptIn(ExperimentalTestApi::class)
class MobileHelpDialogTest {

    @Test
    fun `a snippet example is added from the phone's help dialog`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()

        onNodeWithText(string(Res.string.help_button)).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.HELP_DIALOG).assertIsDisplayed()

        // The examples sit below the fold of the dialog's scroll on a phone-sized scene; a click
        // on an off-scene node lands clamped somewhere else instead of failing.
        // The phone-width card wraps every description, so the examples land far below the
        // content viewport; performScrollTo is a no-op on a fully clipped node (its clipped
        // bounds are zero, so the computed delta is zero) — drive the scroll semantics directly.
        onNodeWithTag(UiTags.HELP_DIALOG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 2000f) }
        waitForIdle()
        onAllNodesWithText(string(Res.string.help_add))[0].performClick()
        waitForIdle()
        onAllNodesWithText(string(Res.string.help_added))[0].assertIsDisplayed()
        assertEquals(
            listOf(SNIPPET_HELP_EXAMPLES.first().label),
            shell.snippets.snippets.map { it.snippet.label },
        )

        onNodeWithText(string(Res.string.help_close)).performClick()
        waitForIdle()
    }
}
