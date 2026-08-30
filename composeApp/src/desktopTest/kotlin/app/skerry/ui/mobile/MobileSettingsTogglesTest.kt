package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_custom_term_theme
import app.skerry.ui.generated.resources.settings_security_report_team_sessions
import app.skerry.ui.generated.resources.settings_terminal_autofit
import app.skerry.ui.generated.resources.settings_terminal_prod_warnings
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's settings switches, against the state they claim to write.
 *
 * Desktop parity for [app.skerry.ui.settings.SettingsTogglesTest]: the same settings are reached on
 * the phone through More → Appearance / Security, and each arrived as a separate `MobileToggleRow`
 * or bare `Toggle` call. A switch bound to the neighbouring field — or to nothing — looks right in
 * every render and is only visible from the state behind it.
 *
 * The production-guard switch is the one that matters most: off, a session on a production host
 * stops warning before a risky command.
 */
@OptIn(ExperimentalTestApi::class)
class MobileSettingsTogglesTest {

    @Test
    fun `the production warnings switch reaches the setting`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Appearance)
        waitForIdle()
        assertFalse(shell.state.confirmProductionWarnings, "the extra warnings are opt-in")

        onSwitch(Res.string.settings_terminal_prod_warnings).assertIsOff().performClick()
        waitForIdle()

        assertTrue(shell.state.confirmProductionWarnings)
        onSwitch(Res.string.settings_terminal_prod_warnings).assertIsOn()
    }

    @Test
    fun `the custom terminal theme switch reaches the setting`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Appearance)
        waitForIdle()
        assertFalse(shell.state.customTerminalTheme)

        onSwitch(Res.string.appearance_custom_term_theme).performClick()
        waitForIdle()

        assertTrue(shell.state.customTerminalTheme)
    }

    @Test
    fun `the auto-fit switch reaches the setting`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Appearance)
        waitForIdle()
        assertFalse(shell.state.terminalAutoFit, "auto-fit is opt-in")

        onSwitch(Res.string.settings_terminal_autofit).assertIsOff().performClick()
        waitForIdle()

        assertTrue(shell.state.terminalAutoFit)
        onSwitch(Res.string.settings_terminal_autofit).assertIsOn()
    }

    @Test
    fun `the team session reporting switch reaches the setting`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Security)
        waitForIdle()
        val before = shell.state.reportTeamSessions

        onSwitch(Res.string.settings_security_report_team_sessions).performClick()
        waitForIdle()

        assertTrue(shell.state.reportTeamSessions != before)
    }
}

/**
 * A switch by the name it carries — the row's caption, which is what [app.skerry.ui.design.Toggle] takes.
 *
 * Scrolled into view first: the phone screen is taller than the 844dp scene, and a click on a node
 * past the bottom edge is clamped to the edge rather than refused, so it silently presses nothing.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.onSwitch(label: StringResource) =
    onNodeWithContentDescription(string(label)).performScrollTo()
