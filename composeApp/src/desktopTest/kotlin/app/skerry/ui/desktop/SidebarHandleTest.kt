package app.skerry.ui.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.UiTags
import app.skerry.ui.host.HostSection
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_hide_hosts
import app.skerry.ui.generated.resources.shell_tip_monitor
import app.skerry.ui.generated.resources.shell_tip_show_hosts
import kotlin.test.Test

/**
 * One control for the hosts panel in both sections: the strip on the panel's own edge, which is
 * where the eye already is when the panel is what you are collapsing. The terminal section used to
 * carry a chevron in its work bar instead — two shapes for one action, and the one in the bar sat
 * over the session rather than beside the panel it moved.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarHandleTest {

    @Test
    fun `the terminal section collapses its hosts panel from the strip beside it`() = runDesktopShell { shell ->
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()

        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertDoesNotExist()
        check(shell.state.sidebarHidden) { "the strip must drive the shared sidebar preference" }

        onNodeWithContentDescription(string(Res.string.shell_tip_show_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()
    }

    /**
     * The rail is how a section is opened, so pressing it has to show that section's catalog. With
     * the panel collapsed the button changed nothing visible, and the only way back was to find the
     * strip and click it too — the user asked for the section, not for the section's absence.
     */
    @Test
    fun `pressing a rail section opens the hosts panel it names`() = runDesktopShell { shell ->
        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertDoesNotExist()

        onScreen(UiTags.railSection(HostSection.RemoteDesktops)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()
        check(!shell.state.sidebarHidden) { "the rail must clear the collapse, not work around it" }
    }

    /** The section already open counts too: the press is still "show me this catalog". */
    @Test
    fun `pressing the section already open reopens its panel`() = runDesktopShell {
        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performClick()
        waitForIdle()

        onScreen(UiTags.railSection(HostSection.Terminal)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()
    }

    /**
     * The monitor keeps the catalog beside its charts, so it owes the same control: without the
     * strip it was the one screen that could show the panel and offer no way to collapse it — and
     * the rail can put the panel there.
     */
    @Test
    fun `the monitor keeps the strip beside the panel it shows`() = runDesktopShell {
        onNodeWithContentDescription(string(Res.string.shell_tip_monitor)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()

        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertDoesNotExist()
    }
}
