package app.skerry.ui.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.host.HostSection
import app.skerry.ui.session.SessionView
import app.skerry.ui.settings.SETTINGS_NAV
import kotlin.test.Test

/**
 * The desktop shell's navigation, driven the way a user drives it: by clicking the rail.
 *
 * What the state layer already proves ([app.skerry.ui.app.DesktopDesignStateTest]) is that
 * `showView` sets the right field. What no test covered is the wiring on either side of it — that
 * the button is actually connected to that call, and that the field actually reaches
 * [Viewport]. That is the half a redesign breaks, and the half only a click can reach.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopNavigationTest {

    @Test
    fun `every app-level rail item opens its own section`() = runDesktopShell {
        val appLevel = RAIL.mapNotNull { (it.target as? RailTarget.View)?.view }
        check(appLevel.isNotEmpty()) { "the rail has no app-level items — the walk would assert nothing" }
        appLevel.forEach { view ->
            onNodeWithTag(UiTags.railView(view)).performClick()
            waitForIdle()
            onScreen(UiTags.screen(view))
                .assertIsDisplayed()
        }
    }

    /**
     * The rail is navigation, not a session switch: opening the desktops catalog while a shell is
     * running swaps the sidebar and leaves the terminal on screen (see [openRailSection]). Getting
     * this wrong doesn't crash — it silently replaces the work the user is in the middle of.
     *
     * `DesktopSectionNavigationTest` already proves the rule as arithmetic — that `workAreaSection`
     * returns Terminal for a shell tab. This is the other half: that the answer reaches the screen.
     */
    @Test
    fun `walking the rail leaves a running session on screen`() = runDesktopShell {
        onNodeWithTag(UiTags.railSection(HostSection.RemoteDesktops)).performClick()
        waitForIdle()
        onScreen(UiTags.screen(SessionView.Terminal)).assertIsDisplayed()
    }

    /** With nothing open there is no session to keep, so the rail decides the whole work area. */
    @Test
    fun `with no session open the rail switches the work area`() = runDesktopShell(withSessions = false) {
        onNodeWithTag(UiTags.railSection(HostSection.RemoteDesktops)).performClick()
        waitForIdle()
        onScreen(UiTags.screen(HostSection.RemoteDesktops)).assertIsDisplayed()

        onNodeWithTag(UiTags.railSection(HostSection.Terminal)).performClick()
        waitForIdle()
        onScreen(UiTags.screen(SessionView.Terminal)).assertIsDisplayed()
    }

    @Test
    fun `a work-area rail item closes the app-level section over it`() = runDesktopShell {
        onNodeWithTag(UiTags.railView(DesktopView.Vault)).performClick()
        waitForIdle()
        onScreen(UiTags.screen(DesktopView.Vault)).assertIsDisplayed()

        onNodeWithTag(UiTags.railSection(HostSection.Terminal)).performClick()
        waitForIdle()
        onScreen(UiTags.screen(DesktopView.Vault)).assertDoesNotExist()
        onScreen(UiTags.screen(SessionView.Terminal)).assertIsDisplayed()
    }

    @Test
    fun `the settings panel opens on the first nav item`() = runDesktopShell {
        onNodeWithTag(UiTags.RAIL_SETTINGS).performClick()
        waitForIdle()
        onScreen(UiTags.SETTINGS_PANEL).assertIsDisplayed()
        onScreen(UiTags.settingsSection(SETTINGS_NAV.first().tab)).assertIsDisplayed()
    }

    @Test
    fun `every settings tab can be reached from the nav`() = runDesktopShell {
        onNodeWithTag(UiTags.RAIL_SETTINGS).performClick()
        waitForIdle()
        SETTINGS_NAV.forEach { item ->
            onNodeWithTag(UiTags.settingsTab(item.tab)).performClick()
            waitForIdle()
            onScreen(UiTags.settingsSection(item.tab)).assertIsDisplayed()
        }
    }

    @Test
    fun `closing the settings panel returns to what was underneath`() = runDesktopShell {
        onNodeWithTag(UiTags.RAIL_SETTINGS).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.SETTINGS_CLOSE).performClick()
        waitForIdle()
        onScreen(UiTags.SETTINGS_PANEL).assertDoesNotExist()
        onScreen(UiTags.screen(SessionView.Terminal)).assertIsDisplayed()
    }
}
