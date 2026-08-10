package app.skerry.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_ai_field_api_key
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.settings_save
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AI settings form, reached the way a user reaches it: rail → Settings → AI.
 *
 * The endpoint is the field worth driving through the UI. A plain-http endpoint sends the API key
 * and the terminal context it is given over the wire in the clear, and the form is where that gets
 * flagged — [app.skerry.ui.ai.InsecureAiEndpointTest] covers the rule, this covers it being shown.
 */
@OptIn(ExperimentalTestApi::class)
class AiSettingsFormTest {

    /**
     * Save is the assertion: an API key typed into a field that reaches nothing is a key the user
     * believes is set, and the failure only shows up as the assistant refusing to answer.
     */
    @Test
    fun `save hands the endpoint and key to the controller`() = runDesktopShell { shell ->
        openAiSettings()
        onField(Res.string.settings_ai_field_endpoint).performTextReplacement(HTTPS_ENDPOINT)
        onField(Res.string.settings_ai_field_api_key).performTextReplacement(KEY)
        onNodeWithText(string(Res.string.settings_save)).performClick()
        waitForIdle()

        assertEquals(HTTPS_ENDPOINT, shell.ai.settings.baseUrl)
        assertEquals(KEY, shell.ai.settings.apiKey)
    }

    /** An http endpoint is accepted but called out: the warning is the whole point of allowing it. */
    @Test
    fun `a plaintext endpoint raises the insecure notice`() = runDesktopShell {
        openAiSettings()
        onField(Res.string.settings_ai_field_endpoint).performTextReplacement(HTTP_ENDPOINT)
        waitForIdle()
        onNodeWithTag(UiTags.AI_INSECURE_ENDPOINT, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an https endpoint raises nothing`() = runDesktopShell {
        openAiSettings()
        onField(Res.string.settings_ai_field_endpoint).performTextReplacement(HTTPS_ENDPOINT)
        waitForIdle()
        onNodeWithTag(UiTags.AI_INSECURE_ENDPOINT, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun ComposeUiTest.openAiSettings() {
        onNodeWithTag(UiTags.RAIL_SETTINGS).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.settingsTab(SettingsTab.AI)).performClick()
        waitForIdle()
    }
}

private const val HTTPS_ENDPOINT = "https://api.example.com/v1"
private const val HTTP_ENDPOINT = "http://api.example.com/v1"
private const val KEY = "sk-test-key"
