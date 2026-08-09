package app.skerry.ui.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rail is nine buttons with no visible text: the label lives in a hover tooltip, which a screen
 * reader never opens and a keyboard user never triggers. Each one therefore has to state its own
 * name, or the whole primary navigation of the desktop app is a column of unnamed buttons.
 *
 * The name has to be the localized label, not the icon — see [app.skerry.ui.design.IconSemanticsTest]
 * for why "vpn_key" is what it would otherwise be.
 */
@OptIn(ExperimentalTestApi::class)
class RailSemanticsTest {

    @Test
    fun `every rail button states its name`() = runDesktopShell {
        railButtons().forEach { (tag, icon) ->
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            val spoken = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            assertTrue(spoken.any { it.isNotBlank() }, "$tag is an unnamed button")
            assertFalse(
                spoken.any { it == icon },
                "$tag is announced by its icon name '$icon' rather than its label",
            )
        }
    }

    /** Nothing under the button reads the glyph out either — the name must be the only thing said. */
    @Test
    fun `no rail button reads out its icon`() = runDesktopShell {
        railButtons().forEach { (tag, icon) ->
            val spoken = onNodeWithTag(tag).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertFalse(spoken.contains(icon), "the rail's '$icon' glyph is part of what $tag says")
        }
    }

    /**
     * Which section is open is drawn as a colour and a 2dp accent bar, neither of which a screen
     * reader can see — so the button has to say it. Asserted both ways round: a rail that reported
     * every button as current would pass a one-sided check.
     */
    @Test
    fun `the open section is the one that reports itself selected`() = runDesktopShell {
        onNodeWithTag(UiTags.railView(DesktopView.Ports)).performClick()
        waitForIdle()

        assertTrue(selected(UiTags.railView(DesktopView.Ports)), "the open section does not say it is current")
        assertFalse(selected(UiTags.railView(DesktopView.Vault)), "a section that is not open says it is")
    }

    private fun ComposeUiTest.selected(tag: String): Boolean =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    /** Tag of each rail button paired with the glyph it draws. */
    private fun railButtons(): List<Pair<String, String>> =
        RAIL.map { item ->
            val tag = when (val target = item.target) {
                is RailTarget.View -> UiTags.railView(target.view)
                is RailTarget.Section -> UiTags.railSection(target.section)
            }
            tag to item.icon
        } + (UiTags.RAIL_SETTINGS to "settings")
}
