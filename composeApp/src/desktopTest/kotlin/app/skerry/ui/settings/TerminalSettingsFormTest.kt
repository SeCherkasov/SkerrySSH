package app.skerry.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numeric settings of the terminal. Not a form in the dialog sense, but the same thing is at
 * stake: a value typed by hand goes straight into the renderer, and one outside the range it can
 * draw at has to be refused rather than clamped silently or taken as-is.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalSettingsFormTest {

    @Test
    fun `a font size typed by hand is applied`() = runDesktopShell { shell ->
        openTerminalSettings()
        onField(Res.string.appearance_font_size, unmerged = true).performTextReplacement("18")
        onField(Res.string.appearance_font_size, unmerged = true).performImeAction()
        waitForIdle()
        assertEquals(18, shell.state.settings.terminalFontSize)
    }

    /**
     * Out of the range the terminal can render, the value is pulled back to the edge rather than
     * rejected — a deliberate clamp at the call site. What must never happen is a size outside the
     * range reaching the renderer.
     */
    @Test
    fun `a font size outside the range is clamped to the edge`() = runDesktopShell { shell ->
        openTerminalSettings()
        val tooBig = (TERMINAL_FONT_SIZE_RANGE.last + 50).toString()
        onField(Res.string.appearance_font_size, unmerged = true).performTextReplacement(tooBig)
        onField(Res.string.appearance_font_size, unmerged = true).performImeAction()
        waitForIdle()

        assertEquals(
            TERMINAL_FONT_SIZE_RANGE.last,
            shell.state.settings.terminalFontSize,
            "a size the terminal cannot draw reached the renderer",
        )
    }

    /** Letters are not a size — the field must leave the setting where it was. */
    @Test
    fun `a non-numeric size leaves the setting alone`() = runDesktopShell { shell ->
        openTerminalSettings()
        val before = shell.state.settings.terminalFontSize
        onField(Res.string.appearance_font_size, unmerged = true).performTextReplacement("big")
        onField(Res.string.appearance_font_size, unmerged = true).performImeAction()
        waitForIdle()
        assertEquals(before, shell.state.settings.terminalFontSize)
    }

    private fun ComposeUiTest.openTerminalSettings() {
        onNodeWithTag(UiTags.RAIL_SETTINGS).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.settingsTab(SettingsTab.Terminal)).performClick()
        waitForIdle()
    }
}
