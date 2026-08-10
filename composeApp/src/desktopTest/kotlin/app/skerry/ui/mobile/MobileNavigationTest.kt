package app.skerry.ui.mobile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onScreen
import app.skerry.ui.desktop.runMobileShell
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mobile shell's navigation. Runs on the desktop JVM against the same common code Android
 * builds — see the note on [app.skerry.ui.desktop.runMobileShell] for what that does and does not
 * cover.
 */
@OptIn(ExperimentalTestApi::class)
class MobileNavigationTest {

    @Test
    fun `every bottom tab opens its screen`() = runMobileShell {
        MobileTab.entries.forEach { tab ->
            onNodeWithTag(UiTags.mobileTab(tab)).performClick()
            waitForIdle()
            onScreen(UiTags.mobileScreen(tab)).assertIsDisplayed()
        }
    }

    /**
     * Which tab is open is drawn as a colour and a weight, so the bar has to say it — desktop parity
     * with the rail ([app.skerry.ui.desktop.RailSemanticsTest]). Asserted for every tab in turn, and
     * for every other tab being *not* current, so a bar that marked them all would fail.
     */
    @Test
    fun `only the open tab reports itself current`() = runMobileShell {
        MobileTab.entries.forEach { open ->
            onNodeWithTag(UiTags.mobileTab(open)).performClick()
            waitForIdle()
            MobileTab.entries.forEach { tab ->
                val current = onNodeWithTag(UiTags.mobileTab(tab)).fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.Selected) == true
                assertEquals(tab == open, current, "$tab said current=$current while $open was open")
            }
        }
    }

    /**
     * Every push screen renders. Opened through the state rather than by tapping its way in: the
     * rows that lead to them are spread over the More hub and the host cards, and this is about the
     * route reaching the right screen, not about how it was raised.
     */
    @Test
    fun `every push route renders its screen`() = runMobileShell { shell ->
        MobileRoute.entries.forEach { route ->
            shell.state.push(route)
            waitForIdle()
            onScreen(UiTags.mobileScreen(route))
                .assertIsDisplayed()
            shell.state.pop()
            waitForIdle()
        }
    }

    /**
     * On the terminal, which is the one push screen a tab is reachable from — every other one hides
     * the bar, and the way back out of those is the system back gesture.
     */
    @Test
    fun `tapping a tab from the terminal closes it`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Terminal)
        waitForIdle()
        onScreen(UiTags.mobileScreen(MobileRoute.Terminal)).assertIsDisplayed()

        onNodeWithTag(UiTags.mobileTab(MobileTab.Hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.mobileScreen(MobileRoute.Terminal)).assertDoesNotExist()
        onScreen(UiTags.mobileScreen(MobileTab.Hosts)).assertIsDisplayed()
    }

    /**
     * The terminal keeps the bottom bar and the remote desktop does not: per the template the
     * terminal is a place you step out of in one tap, while the framebuffer wants the whole display
     * (see [app.skerry.ui.app.mobileRouteKeepsTabBar]). The rule is a pure function with its own
     * test; what is checked here is that the shell lays the bar out accordingly.
     */
    @Test
    fun `the terminal keeps the tab bar and a remote desktop does not`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Terminal)
        waitForIdle()
        onNodeWithTag(UiTags.mobileTab(MobileTab.Hosts)).assertIsDisplayed()

        shell.state.push(MobileRoute.Vnc)
        waitForIdle()
        onNodeWithTag(UiTags.mobileTab(MobileTab.Hosts)).assertDoesNotExist()
    }
}
