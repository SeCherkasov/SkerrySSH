package app.skerry.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.DesktopSettingsState
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_custom_term_theme
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.generated.resources.appearance_recent_count
import app.skerry.ui.generated.resources.appearance_recent_show
import app.skerry.ui.generated.resources.settings_security_report_team_sessions
import app.skerry.ui.generated.resources.term_recent_section
import app.skerry.ui.generated.resources.theme_light
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Settings that change what the rest of the shell does, switched the way a user switches them.
 *
 * The state layer proves the field moves; what nothing covered is the half after it — that the
 * switch is wired to that field at all, and that the field reaches the surface it is about. A
 * setting that flips nothing looks exactly like a setting that works, which is why it is worth a
 * click test and a plain "did the sidebar change" assertion rather than a state read alone.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsTogglesTest {

    @Test
    fun `hiding the recent section takes it out of the sidebar`() = runDesktopShell { shell ->
        connectFromCatalog(PROD_HOST)
        onCatalog(string(Res.string.term_recent_section)).assertIsDisplayed()

        openSettings(SettingsTab.Appearance)
        switch(Res.string.appearance_recent_show).assertIsOn().performClick()
        waitForIdle()
        closeSettings()

        assertTrue(shell.state.recentHostIds.isNotEmpty(), "the history itself must survive hiding it")
        onCatalog(string(Res.string.term_recent_section)).assertDoesNotExist()

        openSettings(SettingsTab.Appearance)
        switch(Res.string.appearance_recent_show).assertIsOff().performClick()
        waitForIdle()
        closeSettings()
        onCatalog(string(Res.string.term_recent_section)).assertIsDisplayed()
    }

    /**
     * The limit is not decoration: a sidebar that keeps drawing every host ever connected to is the
     * bug the setting exists for. With one slot, the older of two recents has to leave.
     */
    @Test
    fun `the recent limit decides how many rows the sidebar keeps`() = runDesktopShell { shell ->
        connectFromCatalog(PROD_HOST)
        connectFromCatalog(SECOND_HOST)

        openSettings(SettingsTab.Appearance)
        setRecentLimit("1")
        closeSettings()

        assertEquals(1, shell.state.settings.recentLimit)
        // Twice for the newest (its catalog row and its recent row), once for the one dropped.
        assertEquals(2, sidebarNodes(SECOND_HOST), "the newest connection belongs in RECENT")
        assertEquals(1, sidebarNodes(PROD_HOST), "the limit must drop the older row")
    }

    @Test
    fun `the default hint restores the recent count`() = runDesktopShell { shell ->
        openSettings(SettingsTab.Appearance)
        setRecentLimit("2")
        assertEquals(2, shell.state.settings.recentLimit)

        val defaults = DesktopSettingsState.MAX_RECENT_HOSTS
        onNodeWithText(string(Res.string.appearance_default_value, defaults.toString()))
            .performScrollTo()
            .performClick()
        waitForIdle()
        assertEquals(defaults, shell.state.settings.recentLimit)
    }

    /** The terminal follows the app theme until this opt-in: the cards do not exist before it. */
    @Test
    fun `the custom terminal theme switch reveals the theme cards`() = runDesktopShell { shell ->
        val other = TerminalThemes.all.first { it.id != shell.state.settings.terminalTheme.id }
        openSettings(SettingsTab.Appearance)
        onNodeWithText(other.displayName).assertDoesNotExist()

        switch(Res.string.appearance_custom_term_theme).performScrollTo().performClick()
        waitForIdle()
        assertTrue(shell.state.settings.customTerminalTheme)

        onNodeWithText(other.displayName).performScrollTo().performClick()
        waitForIdle()
        assertEquals(other.id, shell.state.settings.terminalTheme.id, "the card must apply its theme")

        switch(Res.string.appearance_custom_term_theme).performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(other.displayName).assertDoesNotExist()
    }

    @Test
    fun `an app theme card applies its mode`() = runDesktopShell { shell ->
        assertNotEquals(ThemeMode.LIGHT, shell.state.settings.themeMode, "the seed must not start there")
        openSettings(SettingsTab.Appearance)
        onNodeWithText(string(Res.string.theme_light)).performScrollTo().performClick()
        waitForIdle()
        assertEquals(ThemeMode.LIGHT, shell.state.settings.themeMode)
    }

    /** What this device tells a team about its own sessions — a switch that must reach the setting. */
    @Test
    fun `reporting team sessions is switched from the security tab`() = runDesktopShell { shell ->
        val before = shell.state.settings.reportTeamSessions
        openSettings(SettingsTab.Security)
        switch(Res.string.settings_security_report_team_sessions).performScrollTo().performClick()
        waitForIdle()
        assertEquals(!before, shell.state.settings.reportTeamSessions)
    }

    /** The switch of a settings row, named after the row it belongs to (see `SettingToggleRow`). */
    private fun ComposeUiTest.switch(label: org.jetbrains.compose.resources.StringResource) =
        onNodeWithContentDescription(string(label))

    private fun ComposeUiTest.openSettings(tab: SettingsTab) {
        onNodeWithTag(UiTags.RAIL_SETTINGS).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.settingsTab(tab)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.closeSettings() {
        onNodeWithTag(UiTags.SETTINGS_CLOSE).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.setRecentLimit(value: String) {
        onField(Res.string.appearance_recent_count, unmerged = true).performScrollTo().performTextReplacement(value)
        onField(Res.string.appearance_recent_count, unmerged = true).performImeAction()
        waitForIdle()
    }

    /** Connects [name] from the catalog, confirming the production guard the seeded hosts raise. */
    private fun ComposeUiTest.connectFromCatalog(name: String) {
        onCatalog(name).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
    }

    /** How many nodes of the sidebar draw [text] — a catalog row, a RECENT row, or both. */
    private fun ComposeUiTest.sidebarNodes(text: String): Int =
        onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)))
            .fetchSemanticsNodes().size
}

// Seeded catalog: both are production hosts, so both go through the guard on connect.
private const val PROD_HOST = "prod-web-01"
private const val SECOND_HOST = "db-master"
