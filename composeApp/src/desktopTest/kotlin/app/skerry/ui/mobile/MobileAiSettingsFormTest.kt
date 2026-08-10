package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_ai_field_api_key
import app.skerry.ui.generated.resources.settings_ai_field_endpoint
import app.skerry.ui.generated.resources.settings_save
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone's AI settings. Desktop parity ([app.skerry.ui.settings.AiSettingsFormTest]): the key
 * typed here is sent to whatever the endpoint says, so a plain-http endpoint has to be called out
 * on the phone as well — a laptop is usually on a network its owner picked, a phone is not.
 */
@OptIn(ExperimentalTestApi::class)
class MobileAiSettingsFormTest {

    @Test
    fun `a plaintext endpoint raises the insecure notice`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Ai)
        waitForIdle()
        onField(Res.string.settings_ai_field_endpoint).performTextReplacement(HTTP_ENDPOINT)
        waitForIdle()
        onNodeWithTag(UiTags.AI_INSECURE_ENDPOINT, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an https endpoint raises nothing`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Ai)
        waitForIdle()
        onField(Res.string.settings_ai_field_endpoint).performTextReplacement(HTTPS_ENDPOINT)
        waitForIdle()
        onNodeWithTag(UiTags.AI_INSECURE_ENDPOINT, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Desktop parity: Save is what proves the typed key left the field. */
    @Test
    fun `save hands the key to the controller`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Ai)
        waitForIdle()
        onField(Res.string.settings_ai_field_api_key).performTextReplacement(KEY)
        onNodeWithText(string(Res.string.settings_save)).performClick()
        waitForIdle()

        assertEquals(KEY, shell.ai.settings.apiKey)
    }
}

private const val HTTPS_ENDPOINT = "https://api.example.com/v1"
private const val HTTP_ENDPOINT = "http://api.example.com/v1"
private const val KEY = "sk-phone-key"
