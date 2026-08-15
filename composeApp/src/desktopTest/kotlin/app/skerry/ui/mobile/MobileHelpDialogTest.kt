package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.MobileShell
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_add
import app.skerry.ui.generated.resources.help_added
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.help_close
import app.skerry.ui.generated.resources.lib_snippets_help_var_timestamp
import app.skerry.ui.snippet.SNIPPET_HELP_EXAMPLES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's help dialog: same composable as the desktop one, but reached through
 * [MobilePushHeader]'s actions slot — the wiring this test exists for.
 */
@OptIn(ExperimentalTestApi::class)
class MobileHelpDialogTest {

    @Test
    fun `a snippet example is added from the phone's help dialog`() = runMobileShell { shell ->
        openHelp(shell, MobileRoute.Snippets)
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

    /**
     * Issue #256: a syntax badge that eats the row leaves the description a two-word column at the
     * right edge. Measured on both dialogs that draw those rows, on the phone width where it breaks
     * first: the badge and its description are read from the same row, so the share is of the space
     * the row actually has, not of the padded card around it.
     */
    @Test
    fun `no syntax badge crowds its description on a phone`() = runMobileShell { shell ->
        // Snippets and runbooks: the third dialog drawing these rows (the vault's) opens only over a
        // live credential controller, which the phone harness does not seed.
        listOf(MobileRoute.Snippets, MobileRoute.Runbooks).forEach { route ->
            openHelp(shell, route)
            val rows = onAllNodesWithTag(UiTags.HELP_ROW, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(rows.isNotEmpty(), "$route documents its syntax in help rows")
            val crowded = rows
                // The row itself carries no text in the unmerged tree — name the offender by its badge.
                .map { row -> row.children.first().text() to row.descriptionShare() }
                .filter { (_, share) -> share < MIN_DESCRIPTION_SHARE_PERCENT }
            assertEquals(emptyList(), crowded, "$route: descriptions under $MIN_DESCRIPTION_SHARE_PERCENT% of the row")
            onNodeWithText(string(Res.string.help_close)).performClick()
            waitForIdle()
        }
    }

    /** Each moment placeholder is documented on its own row, so no badge has to carry three. */
    @Test
    fun `date, time and timestamp are separate rows`() = runMobileShell { shell ->
        openHelp(shell, MobileRoute.Snippets)

        val badges = onAllNodesWithTag(UiTags.HELP_CODE, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.text() }
        assertTrue(
            badges.containsAll(listOf("\${{date}}", "\${{time}}", "\${{timestamp}}")),
            "each moment placeholder has its own row, was $badges",
        )
    }

    /**
     * One row, one stop: a screen reader must announce the placeholder together with what it does.
     * Split across two nodes, the badge reads as `$ { { timestamp } }` and its meaning arrives a
     * swipe later, unattached — the same reason [app.skerry.ui.design.KeyValueRow] merges.
     */
    @Test
    fun `a syntax row announces the placeholder with its description`() = runMobileShell { shell ->
        openHelp(shell, MobileRoute.Snippets)

        val description = string(Res.string.lib_snippets_help_var_timestamp)
        val announced = onNodeWithText(description, substring = true).fetchSemanticsNode().text()
        assertTrue(
            announced.contains("\${{timestamp}}") && announced.contains(description),
            "the row is one accessibility node, was \"$announced\"",
        )
    }

    private fun ComposeUiTest.openHelp(shell: MobileShell, route: MobileRoute) {
        shell.state.push(route)
        waitForIdle()
        onNodeWithText(string(Res.string.help_button)).performClick()
        waitForIdle()
    }

    /** How much of the row is left for the description, in percent of the row's width. */
    private fun SemanticsNode.descriptionShare(): Int {
        val description = children.last()
        return description.size.width * 100 / size.width
    }

    /** Everything the node carries as text — one badge, or a whole row once it is merged. */
    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    private companion object {
        /**
         * A description narrower than a quarter of its row wraps to the two-word column issue #256
         * reported. The widest badge that survives the fix — `${'$'}{{env:dev|staging|prod}}` — leaves its
         * description around a third of the row, so the floor is not a measurement of today's copy.
         */
        const val MIN_DESCRIPTION_SHARE_PERCENT = 25
    }
}
