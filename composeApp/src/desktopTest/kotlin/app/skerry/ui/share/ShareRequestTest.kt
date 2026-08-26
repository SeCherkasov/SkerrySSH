package app.skerry.ui.share

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.skerry.ui.terminal.ToolbarRequest
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * What the share button does with an ask it cannot serve.
 *
 * The overflow menu raises the ask rather than opening the panel itself, and the row is lit whenever
 * the pane has a terminal — including with sync disconnected, where there is no share controller and
 * the button draws nothing at all. An ask left pending there does not disappear: it waits for the
 * next composition that gets past the guards, and opens the streaming-consent panel over whatever
 * session is active by then.
 */
@OptIn(ExperimentalTestApi::class)
class ShareRequestTest {

    @Test
    fun `an ask nobody can serve is taken and dropped`() = runDesktopComposeUiTest {
        val request = ToolbarRequest()
        setContent {
            SkerryTheme {
                // No controller: what the toolbar composes with sync disconnected.
                ShareSessionButton(session = null, controller = null, teams = emptyList(), request = request)
            }
        }
        waitForIdle()

        request.raise()
        waitForIdle()

        assertFalse(request.pending, "the ask is parked for a later session to answer")
    }
}
